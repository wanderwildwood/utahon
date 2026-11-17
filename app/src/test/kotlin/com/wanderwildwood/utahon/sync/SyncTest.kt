package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-way sync against a made-up Nextcloud held in memory: files with ids that a rename keeps,
 * etags that move whenever a file does, If-Match honoured. Each test is one phone and one
 * server, synced, changed, and synced again.
 */
class SyncTest {

    class FakeCloud : Remote {
        class File(val id: String, var text: String, var etag: String)

        val files = linkedMapOf<String, File>()
        private var next = 100
        private fun tag() = "\"e${next++}\""

        /** Changing a file underneath, between a sync's listing and its write. */
        var meddle: (() -> Unit)? = null

        /** As another program or the web would: a file written there. */
        fun putThere(file: String, text: String) {
            val f = files[file]
            if (f == null) files[file] = File("${next++}", text, tag()) else { f.text = text; f.etag = tag() }
        }

        /** Files are kept by their path under the song folder: "Ada Whitlock/Lantern Road.cho". */
        override fun list(): Listing {
            val songs = files.filterKeys { SongFile.isSong(it) && it.count { c -> c == '/' } <= 1 }.map { (path, f) ->
                val folder = path.substringBeforeLast('/', "")
                val file = path.substringAfterLast('/')
                val stem = file.substringBeforeLast('.')
                f.id to RemoteSong(f.id, folder, stem, file.substring(stem.length), f.etag)
            }.toMap()
            return Listing(songs, songs.values.map { it.place }.toSet())
        }

        private fun fileOf(r: RemoteSong) = files.entries.firstOrNull { it.value.id == r.fileId }

        override fun read(r: RemoteSong): Pair<String, String> {
            val f = fileOf(r)?.value ?: throw Changed("gone")
            return f.text to f.etag
        }

        override fun create(folder: String, name: String, text: String): RemoteSong {
            val file = placeOf(folder, name) + SongFile.EXTENSION
            if (files.keys.any { it.equals(file, ignoreCase = true) }) throw Taken(file)
            val f = File("${next++}", text, tag())
            files[file] = f
            return RemoteSong(f.id, folder, name, SongFile.EXTENSION, f.etag)
        }

        override fun write(r: RemoteSong, text: String): String {
            meddle?.let { meddle = null; it() }
            val f = fileOf(r)?.value ?: throw Changed("gone")
            if (f.etag != r.etag) throw Changed("changed")
            f.text = text
            f.etag = tag()
            return f.etag
        }

        override fun move(r: RemoteSong, folder: String, to: String): Boolean {
            val e = fileOf(r) ?: throw Changed("gone")
            val path = placeOf(folder, to) + r.ending
            if (files.keys.any { it.equals(path, ignoreCase = true) }) return false
            files.remove(e.key)
            files[path] = e.value
            return true
        }

        override fun delete(r: RemoteSong) {
            val e = fileOf(r) ?: return
            if (e.value.etag != r.etag) throw Changed("changed")
            files.remove(e.key)
        }

        override fun readOwn(file: String): Pair<String, String>? = files[file]?.let { it.text to it.etag }

        override fun writeOwn(file: String, text: String, etag: String?): String {
            meddle?.let { meddle = null; it() }
            val f = files[file]
            if (f?.etag != etag) throw Changed("changed")
            putThere(file, text)
            return files.getValue(file).etag
        }
    }

    class Phone : Local {
        val all = linkedMapOf<String, Song>()
        var cannotRead = emptySet<String>()
        override fun unreadable(): Set<String> = cannotRead
        private var ids = 0
        private var clock = 0
        override fun songs(): List<Song> = all.values.toList()
        override fun put(song: Song, expect: String?): Boolean {
            if (all[song.id]?.modified != expect) return false
            all[song.id] = song
            return true
        }
        override fun remove(id: String, expect: String): Boolean {
            val now = all[id] ?: return true
            if (now.modified != expect) return false
            all.remove(id)
            return true
        }
        override fun newId() = "new-${ids++}"
        override fun now() = "t${clock++}"

        /** As the reader would: a song saved here. */
        fun save(song: Song): Song = song.copy(modified = now()).also { all[it.id] = it }
    }

    private val cloud = FakeCloud()
    private val phone = Phone()
    private var links = emptyMap<String, Link>()

    private fun sync() {
        val s = Sync(cloud, phone, links, " (phone)")
        s.run()
        links = s.links.toMap()
    }

    private fun lantern(text: String = "G    C\nLantern road\n") = Song(id = "a", title = "Lantern Road", artist = "Ada Whitlock", text = text, capo = 2)

    private fun titles() = phone.songs().map { it.title }.sorted()

    @Test fun aSongHereGoesThere() {
        phone.save(lantern())
        sync()
        assertEquals(setOf("Ada Whitlock/Lantern Road.cho"), cloud.files.keys)
        val text = cloud.files.values.single().text
        assertTrue(text, text.contains("{title: Lantern Road}") && text.contains("[G]Lante[C]rn road") && text.contains("{x_songbook_id: a}"))
        assertEquals(1, links.size)
        // Nothing changed: nothing written again.
        val etag = cloud.files.values.single().etag
        sync()
        assertEquals(etag, cloud.files.values.single().etag)
    }

    @Test fun aSongThereComesHere() {
        cloud.putThere("Paper Boats.cho", "{title: Paper Boats}\n{artist: Tomas Reyes}\n[Am]Folded up the [F]morning news\n")
        cloud.putThere("notes.txt", "not a song")
        sync()
        val s = phone.songs().single()
        assertEquals("Paper Boats", s.title)
        assertEquals("Tomas Reyes", s.artist)
        assertEquals("Am            F\nFolded up the morning news\n", s.text)
        assertTrue(s.modified.isNotEmpty())
        // It is put in its artist's folder, and not sent straight back.
        val etag = cloud.files.getValue("Tomas Reyes/Paper Boats.cho").etag
        sync()
        assertEquals(etag, cloud.files.getValue("Tomas Reyes/Paper Boats.cho").etag)
        assertEquals(setOf("Tomas Reyes/Paper Boats.cho", "notes.txt"), cloud.files.keys)
        assertEquals(1, phone.songs().size)
    }

    @Test fun aChangeHereIsSentAndARenameFollows() {
        phone.save(lantern())
        sync()
        phone.save(phone.all.getValue("a").copy(title = "Lantern Lane", transpose = 2))
        sync()
        assertEquals(setOf("Ada Whitlock/Lantern Lane.cho"), cloud.files.keys)
        assertTrue(cloud.files.values.single().text.contains("{x_songbook_transpose: 2}"))
        assertEquals("Ada Whitlock/Lantern Lane", links.getValue("a").remote.place)
        // Given to another artist, it moves to that artist's folder; with no artist, to the top.
        phone.save(phone.all.getValue("a").copy(artist = "Tomas Reyes"))
        sync()
        assertEquals(setOf("Tomas Reyes/Lantern Lane.cho"), cloud.files.keys)
        phone.save(phone.all.getValue("a").copy(artist = ""))
        sync()
        assertEquals(setOf("Lantern Lane.cho"), cloud.files.keys)
    }

    @Test fun aChangeThereIsFetched() {
        phone.save(lantern())
        sync()
        cloud.putThere("Ada Whitlock/Lantern Road.cho", "{title: Lantern Road}\n{artist: Ada Whitlock}\n{capo: 4}\n[D]Lantern road\n")
        sync()
        val s = phone.songs().single()
        assertEquals("a", s.id)
        assertEquals(4, s.capo)
        assertEquals("D\nLantern road\n", s.text)
    }

    @Test fun changedOnBothSidesKeepsBoth() {
        phone.save(lantern())
        sync()
        phone.save(phone.all.getValue("a").copy(text = "Em\nMine now\n"))
        cloud.putThere("Ada Whitlock/Lantern Road.cho", "{title: Lantern Road}\n{artist: Ada Whitlock}\n[D]Theirs now\n")
        sync()
        assertEquals(listOf("Lantern Road", "Lantern Road (phone)"), titles())
        assertEquals("D\nTheirs now\n", phone.all.getValue("a").text)
        assertEquals(2, cloud.files.size)
        assertTrue(cloud.files.keys.any { it.startsWith("Ada Whitlock/Lantern Road (phone)") })
    }

    @Test fun deletedHereIsDeletedThere() {
        phone.save(lantern())
        sync()
        phone.all.remove("a")
        sync()
        assertTrue(cloud.files.isEmpty())
        assertTrue(links.isEmpty())
    }

    @Test fun deletedThereIsDeletedHere() {
        phone.save(lantern())
        sync()
        cloud.files.clear()
        sync()
        assertTrue(phone.songs().isEmpty())
        assertTrue(links.isEmpty())
    }

    @Test fun aChangeBeatsADeletion() {
        phone.save(lantern())
        sync()
        // Deleted there, changed here: it goes up again.
        cloud.files.clear()
        phone.save(phone.all.getValue("a").copy(capo = 5))
        sync()
        assertEquals(1, cloud.files.size)
        assertEquals(1, phone.songs().size)
        // Deleted here, changed there: it comes back.
        phone.all.clear()
        cloud.putThere(cloud.files.keys.single(), "{title: Lantern Road}\n{artist: Ada Whitlock}\n[A]Back again\n")
        sync()
        assertEquals("A\nBack again\n", phone.songs().single().text)
    }

    @Test fun theSameSongOnBothSidesIsOneSong() {
        val s = phone.save(lantern())
        cloud.putThere("Ada Whitlock/Lantern Road.cho", SongFile.toFile(s.copy(id = "")))
        sync()
        assertEquals(1, phone.songs().size)
        assertEquals(1, cloud.files.size)
        assertEquals(1, links.size)
    }

    @Test fun songsSentBeforeArtistsHadFoldersAreMovedIntoThem() {
        // As 0.1.6 left them: every song in the one folder, named "Title - Artist".
        val s = phone.save(lantern())
        val other = phone.save(Song(id = "b", title = "Loose Leaf", text = "G\nla\n"))
        cloud.putThere("Lantern Road - Ada Whitlock.cho", SongFile.toFile(s))
        cloud.putThere("Loose Leaf.cho", SongFile.toFile(other))
        val old = cloud.files.getValue("Lantern Road - Ada Whitlock.cho")
        links = mapOf(
            "a" to Link("a", RemoteSong(old.id, "", "Lantern Road - Ada Whitlock", ".cho", old.etag), s.modified),
            "b" to Link("b", RemoteSong(cloud.files.getValue("Loose Leaf.cho").id, "", "Loose Leaf", ".cho", cloud.files.getValue("Loose Leaf.cho").etag), other.modified),
        )
        sync()
        assertEquals(setOf("Ada Whitlock/Lantern Road.cho", "Loose Leaf.cho"), cloud.files.keys)
        assertEquals(old.id, cloud.files.getValue("Ada Whitlock/Lantern Road.cho").id)
        assertEquals("Ada Whitlock", links.getValue("a").remote.folder)
        assertEquals(2, phone.songs().size)
        // With the links lost as well (signed in afresh), the old names are still known for the same songs.
        cloud.files.clear()
        cloud.putThere("Lantern Road - Ada Whitlock.cho", SongFile.toFile(s.copy(id = "")))
        links = emptyMap()
        phone.all.remove("b")
        sync()
        assertEquals(setOf("Ada Whitlock/Lantern Road.cho"), cloud.files.keys)
        assertEquals(1, phone.songs().size)
    }

    @Test fun aFileWithNoArtistInItIsByItsFolder() {
        cloud.putThere("Tomas Reyes/Kettle Song.cho", "{title: Kettle Song}\n[D]Kettle on\n")
        sync()
        assertEquals("Tomas Reyes", phone.songs().single().artist)
        assertEquals(setOf("Tomas Reyes/Kettle Song.cho"), cloud.files.keys)
    }

    @Test fun twoDifferentSongsOfOneNameAreBothKept() {
        phone.save(lantern())
        cloud.putThere("Ada Whitlock/Lantern Road.cho", "{title: Lantern Road}\n{artist: Ada Whitlock}\n[D]Another one entirely\n")
        sync()
        assertEquals(listOf("Lantern Road", "Lantern Road (phone)"), titles())
        assertEquals(2, cloud.files.size)
    }

    @Test fun aFolderSwappedOnTheServerDeletesNothingHere() {
        for (i in 1..4) phone.save(Song(id = "s$i", title = "Song $i", text = "G\nla $i\n"))
        sync()
        assertEquals(4, cloud.files.size)
        cloud.files.clear()
        sync()
        assertEquals(4, phone.songs().size)
        assertEquals(4, cloud.files.size)
    }

    @Test fun aSongThisPhoneCouldNotReadIsNotDeletedThere() {
        phone.save(lantern())
        sync()
        phone.all.remove("a"); phone.cannotRead = setOf("a")
        sync()
        assertEquals(1, cloud.files.size)
    }

    @Test fun aPhoneWithNoneOfItsSongsDeletesNothingThere() {
        for (i in 1..4) phone.save(Song(id = "s$i", title = "Song $i", text = "G\nla $i\n"))
        sync()
        phone.all.clear()
        sync()
        assertEquals(4, cloud.files.size)
    }

    @Test fun aFileChangedUnderAWriteIsLeftForNextTime() {
        phone.save(lantern())
        sync()
        phone.save(phone.all.getValue("a").copy(capo = 7))
        cloud.meddle = { cloud.putThere("Ada Whitlock/Lantern Road.cho", "{title: Lantern Road}\n{artist: Ada Whitlock}\n[D]Slipped in\n") }
        sync()
        // Nothing lost: the server's text is still there, and the next sync sees both changed.
        assertTrue(cloud.files.values.single().text.contains("Slipped in"))
        sync()
        assertEquals(listOf("Lantern Road", "Lantern Road (phone)"), titles())
    }

    @Test fun linksSurviveBeingWrittenDown() {
        phone.save(lantern())
        sync()
        assertEquals(links, Link.listFromJson(Link.listToJson(links.values)))
        assertTrue(Link.listFromJson("not json").isEmpty())
    }

    @Test fun aSongChangedDuringTheSyncIsNotOverwritten() {
        assertFalse(phone.put(lantern().copy(modified = "x"), "something-else"))
        assertNull(phone.all["a"])
    }
}
