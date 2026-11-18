package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * The WebDAV calls themselves, over real HTTP, against a small server on this machine that
 * answers the way a Nextcloud does as far as a sync leans on it: listings with file ids and
 * etags, If-Match and If-None-Match honoured, a folder that has to exist before a file goes in
 * it, MOVE refusing to write over, and no way in without the password.
 */
class NextcloudFolderTest {

    private class Stored(val id: Int, var text: String, var etag: String)

    private val server = MockWebServer()
    private val folders = mutableSetOf("/remote.php/dav/files/ada/")
    private val files = linkedMapOf<String, Stored>()
    private var next = 100
    private var gzipMarks = true
    private fun tag() = "\"e${next++}\""
    /** Run as a folder's DELETE arrives, before it is answered: another phone's write in between. */
    private var beforeFolderDelete: (() -> Unit)? = null

    /** A folder's etag, as Nextcloud's: it changes whenever anything in it does. */
    private fun folderTag(path: String) =
        "\"d" + (files.filterKeys { it.startsWith(path) }.map { it.key + it.value.etag } +
            folders.filter { it != path && it.startsWith(path) }).hashCode().toUInt() + "\""

    private fun answer(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): MockResponse {
        val r = MockResponse().setResponseCode(code)
        headers.forEach { (k, v) -> r.addHeader(k, v) }
        if (body.isNotEmpty()) r.setBody(body)
        return r
    }

    private fun entry(path: String, etag: String, id: Int, folder: Boolean) =
        "<d:response><d:href>${encodePath(path)}</d:href><d:propstat><d:prop><d:getetag>$etag</d:getetag>" +
            "<d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><oc:fileid>$id</oc:fileid></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun handle(x: RecordedRequest): MockResponse {
        val path = URLDecoder.decode(x.requestUrl!!.encodedPath.replace("+", "%2B"), "UTF-8")
        val body = x.body.readUtf8()
        if (x.getHeader("Authorization") != okhttp3.Credentials.basic("ada", "app-password")) return answer(401)
        val ifMatch = x.getHeader("If-Match")
        return when (x.method) {
            "PROPFIND" -> {
                val out = StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
                if (path in folders) {
                    out.append(entry(path, folderTag(path), 1, true))
                    if (x.getHeader("Depth") == "1") {
                        folders.filter { it != path && it.startsWith(path) && !it.removePrefix(path).trimEnd('/').contains('/') }.forEach { out.append(entry(it, folderTag(it), 2, true)) }
                        files.filterKeys { it.startsWith(path) && !it.removePrefix(path).contains('/') }.forEach { (p, f) -> out.append(entry(p, f.etag, f.id, false)) }
                    }
                } else {
                    val f = files[path] ?: return answer(404)
                    out.append(entry(path, f.etag, f.id, false))
                }
                answer(207, out.append("</d:multistatus>").toString())
            }
            "MKCOL" -> if (path in folders) answer(405) else if (path.trimEnd('/').substringBeforeLast('/') + "/" !in folders) answer(409) else { folders += path; answer(201) }
            "PUT" -> {
                val was = files[path]
                when {
                    path.substringBeforeLast('/') + "/" !in folders -> answer(409)
                    x.getHeader("If-None-Match") == "*" && was != null -> answer(412)
                    ifMatch != null && was?.etag != ifMatch -> answer(412)
                    else -> {
                        val f = was ?: Stored(next++, "", "").also { files[path] = it }
                        f.text = body
                        f.etag = tag()
                        answer(if (was == null) 201 else 204, headers = mapOf("OC-ETag" to f.etag))
                    }
                }
            }
            // As a server that compresses does: the etag sent with the file is marked, and is not
            // letter for letter the one the listing gives.
            "GET" -> files[path]?.let { answer(200, it.text, mapOf("ETag" to (if (gzipMarks) "W/" + it.etag.dropLast(1) + "-gzip\"" else it.etag))) } ?: answer(404)
            "MOVE" -> {
                val to = URLDecoder.decode(URI(x.getHeader("Destination")).rawPath.replace("+", "%2B"), "UTF-8")
                val f = files[path] ?: return answer(404)
                if (to in files) return answer(412)
                if (to.substringBeforeLast('/') + "/" !in folders) return answer(409)
                files.remove(path)
                files[to] = f
                answer(201)
            }
            "DELETE" -> {
                // A folder goes with everything in it, as WebDAV has it.
                if (path in folders) {
                    beforeFolderDelete?.invoke()
                    if (ifMatch != null && folderTag(path) != ifMatch) return answer(412)
                    files.keys.removeAll { it.startsWith(path) }
                    folders.removeAll { it.startsWith(path) }
                    return answer(204)
                }
                val f = files[path] ?: return answer(404)
                if (ifMatch != null && f.etag != ifMatch) return answer(412)
                files.remove(path)
                answer(204)
            }
            else -> answer(405)
        }
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) { runCatching { handle(request) }.getOrElse { answer(500) } }
        }
        server.start()
    }

    @After fun stop() { runCatching { server.shutdown() } }

    private fun account(password: String = "app-password") = Account("http://127.0.0.1:${server.port}", "ada", password)
    private fun folder() = NextcloudFolder(account(), "Music/Songbook")
    private val root = "/remote.php/dav/files/ada/Music/Songbook/"

    @Test fun theFirstListingMakesTheFolder() {
        assertEquals(Listing(emptyMap(), emptySet(), made = true), folder().list())
        assertTrue(root in folders && "/remote.php/dav/files/ada/Music/" in folders)
        assertFalse(folder().list().made)
    }

    @Test fun createsReadsWritesRenamesAndDeletes() {
        val f = folder()
        f.list()
        val made = f.create("Ada Whitlock", "Lantern Road", "one")
        assertEquals("Ada Whitlock/Lantern Road", made.place)
        assertEquals(".cho", made.ending)
        assertTrue(root + "Ada Whitlock/" in folders)
        assertEquals(mapOf(made.fileId to made), f.list().songs)
        assertEquals("one" to made.etag, f.read(made))
        assertThrows(Taken::class.java) { f.create("Ada Whitlock", "Lantern Road", "again") }

        val etag = f.write(made, "two")
        assertFalse(etag == made.etag)
        assertThrows(Changed::class.java) { f.write(made, "stale") }
        val now = made.copy(etag = etag)
        assertEquals("two", f.read(now).first)

        f.create("", "Other", "x")
        assertFalse(f.move(now, "", "Other"))
        // To another artist's folder, which is made for it; the one it leaves empty goes.
        assertTrue(f.move(now, "Tomas & Co", "Lantern Lane"))
        assertFalse(root + "Ada Whitlock/" in folders)
        val listed = f.list()
        assertEquals(setOf("Tomas & Co/Lantern Lane", "Other"), listed.places)
        assertEquals(now.fileId, listed.songs.values.first { it.name == "Lantern Lane" }.fileId)

        val moved = listed.songs.getValue(now.fileId)
        assertThrows(Changed::class.java) { f.delete(moved.copy(etag = "\"old\"")) }
        f.delete(moved)
        assertEquals(setOf("Other"), f.list().places)
        assertFalse(root + "Tomas & Co/" in folders)
    }

    @Test fun anEmptiedFolderKeepsASongPutInItFromAnotherPhoneJustBefore() {
        val f = folder()
        f.list()
        val made = f.create("Ada Whitlock", "Lantern Road", "one")
        val theirs = root + "Ada Whitlock/Harbor Light.cho"
        beforeFolderDelete = { files[theirs] = Stored(next++, "theirs", tag()) }
        f.delete(made)
        assertTrue(root + "Ada Whitlock/" in folders)
        assertEquals("theirs", files.getValue(theirs).text)
        assertEquals(setOf("Ada Whitlock/Harbor Light"), f.list().places)

        // Nothing put in it on the way: the emptied folder goes.
        beforeFolderDelete = null
        f.delete(f.list().songs.values.single())
        assertFalse(root + "Ada Whitlock/" in folders)
    }

    @Test fun onlySongFilesAreListed() {
        folder().list()
        files[root + "notes.txt"] = Stored(7, "x", "\"n\"")
        files[root + "A Song.pro"] = Stored(8, "x", "\"p\"")
        folders += root + "Ada Whitlock/"
        folders += root + "Ada Whitlock/Deeper/"
        files[root + "Ada Whitlock/In A Folder.cho"] = Stored(9, "x", "\"q\"")
        files[root + "Ada Whitlock/cover.jpg"] = Stored(10, "x", "\"j\"")
        files[root + "Ada Whitlock/Deeper/Too Deep.cho"] = Stored(11, "x", "\"d\"")
        val l = folder().list()
        assertEquals(setOf("A Song", "Ada Whitlock/In A Folder"), l.places)
        assertEquals(".pro", l.songs.getValue("8").ending)
        assertEquals("Ada Whitlock", l.songs.getValue("9").folder)
    }

    @Test fun anEtagReadWithTheFileIsTheListingsEtag() {
        val f = folder()
        f.list()
        val made = f.create("", "Lantern Road", "one")
        assertEquals(made.etag, f.read(made).second)
        assertEquals(made.etag, f.list().songs.getValue(made.fileId).etag)
        assertEquals("\"abc\"", canonicalEtag("W/\"abc-gzip\""))
        assertEquals("\"abc\"", canonicalEtag("abc"))
        assertEquals("\"abc\"", canonicalEtag("\"abc\""))
    }

    @Test fun setlistsSettleOverTheWireWithoutCopies() {
        val phone = object : LocalLists {
            var all = listOf<com.wanderwildwood.utahon.data.Setlist>()
            var mark = "s0"
            var n = 0
            override fun lists() = all
            override fun stamp() = mark
            override fun put(lists: List<com.wanderwildwood.utahon.data.Setlist>, expect: String): String? {
                if (mark != expect) return null
                all = lists; mark = "s${++n}"; return mark
            }
            override fun newId() = "new-${++n}"
            fun change(lists: List<com.wanderwildwood.utahon.data.Setlist>) { all = lists; mark = "s${++n}" }
        }
        var link: ListsLink? = null
        fun sync() { link = SetlistSync.run(folder(), phone, link, " (phone)") }
        folder().list()
        val friday = com.wanderwildwood.utahon.data.Setlist("f", "Friday")
        // Made here, sent; songs added here twice, sent each time: one list, never a copy.
        phone.change(listOf(friday)); sync()
        phone.change(listOf(friday.copy(songs = listOf("a")))); sync()
        phone.change(listOf(friday.copy(songs = listOf("a", "b")))); sync()
        sync()
        assertEquals(listOf(friday.copy(songs = listOf("a", "b"))), phone.all)
        assertEquals(phone.all, SetlistSync.fromFile(files.getValue(root + SetlistSync.FILE).text))
        // Changed there: taken here, once, and then quiet.
        files.getValue(root + SetlistSync.FILE).apply { text = SetlistSync.toFile(listOf(friday.copy(songs = listOf("b")))); etag = tag() }
        sync()
        assertEquals(listOf("b"), phone.all.single().songs)
        val after = files.getValue(root + SetlistSync.FILE).etag
        sync()
        assertEquals(after, files.getValue(root + SetlistSync.FILE).etag)
        assertEquals(1, phone.all.size)
    }

    @Test fun aSongFetchedIsNotFetchedAgainNorCopiedWhenChangedHere() {
        val phone = SyncTest.Phone()
        var links = emptyMap<String, Link>()
        fun sync() { Sync(folder(), phone, links, " (phone)").also { it.run(); links = it.links.toMap() } }
        folder().list()
        files[root + "Paper Boats.cho"] = Stored(next++, "{title: Paper Boats}\n[Am]Folded up the [F]morning news\n", tag())
        sync()
        val got = phone.songs().single()
        // Changed here after it came down: sent up, one song still, no "(phone)" copy.
        phone.save(got.copy(capo = 2))
        sync()
        assertEquals(listOf("Paper Boats"), phone.songs().map { it.title })
        assertEquals(1, files.size)
        assertTrue(files.values.single().text.contains("{capo: 2}"))
    }

    @Test fun aWrongPasswordIsRefusedAndASilentServerUnreachable() {
        assertThrows(Refused::class.java) { NextcloudFolder(account("wrong"), "Songbook").probe() }
        folder().probe()
        server.shutdown()
        assertThrows(Unreachable::class.java) { folder().list() }
    }

    @Test fun aWholeSyncGoesOverTheWire() {
        val phone = SyncTest.Phone()
        phone.save(Song(id = "a", title = "Lantern Road", artist = "Ada Whitlock", text = "G    C\nLantern road\n", capo = 2))
        var links = emptyMap<String, Link>()
        fun sync() { Sync(folder(), phone, links, " (phone)").also { it.run(); links = it.links.toMap() } }
        sync()
        val path = root + "Ada Whitlock/Lantern Road.cho"
        assertTrue(files.getValue(path).text.contains("[G]Lante[C]rn road"))
        // A song put there by hand comes here; a change there comes here; a change here goes there.
        files[root + "Paper Boats.cho"] = Stored(next++, "{title: Paper Boats}\n[Am]Folded up the [F]morning news\n", tag())
        files.getValue(path).apply { text = text.replace("{capo: 2}", "{capo: 4}"); etag = tag() }
        sync()
        assertEquals(4, phone.all.getValue("a").capo)
        assertEquals(setOf("Lantern Road", "Paper Boats"), phone.songs().map { it.title }.toSet())
        phone.save(phone.all.getValue("a").copy(title = "Lantern Lane"))
        sync()
        assertTrue(root + "Ada Whitlock/Lantern Lane.cho" in files)
        assertFalse(path in files)
        // Deleted here: gone there, and its artist's folder with it.
        phone.all.remove("a")
        sync()
        assertEquals(setOf(root + "Paper Boats.cho"), files.keys)
        assertFalse(root + "Ada Whitlock/" in folders)
    }
}
