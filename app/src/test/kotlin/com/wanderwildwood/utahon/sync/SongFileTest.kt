package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SongFileTest {

    private val song = Song(
        id = "a1", title = "Lantern Road", artist = "Ada Whitlock", kind = "Chords", capo = 2, tuning = "D A D G B E", key = "G",
        url = "https://tabs.example.com/tab/1", transpose = -2, speed = 6, length = 220,
        created = "2026-11-11T10:00:00-05:00", modified = "2026-11-11T10:05:00-05:00",
        text = "[Intro]\ne|-3-------0-------|\nB|---0-------1-----|\nG  C  G  D\n\n[Verse 1]\nG              C\nLantern on the gatepost\n          G       D\nShows the road is long\n\nPlay it slowly.\n",
    )

    @Test fun aSongComesBackFromItsFileAsItWent() {
        val back = SongFile.fromFile(SongFile.toFile(song), "Lantern Road - Ada Whitlock.cho")
        assertEquals(song, back)
    }

    @Test fun aSheetComesBackWordForWordEvenWhereChordProWouldChangeIt() {
        val odd = song.copy(text = "   spoken, not sung  \n\n\n\nG    C\nwords with a \\ and {braces}\n\n")
        val back = SongFile.fromFile(SongFile.toFile(odd), "x.cho")!!
        assertEquals(odd.text, back.text)
    }

    @Test fun aSheetChangedElsewhereIsReadFromTheBody() {
        val odd = song.copy(text = "   spoken, not sung  \n\n\n\nG    C\nwords\n")
        val file = SongFile.toFile(odd)
        val body = file.indexOf("{title:")
        val edited = file.substring(0, body) + file.substring(body).replace("words", "other words")
        val back = SongFile.fromFile(edited, "x.cho")!!
        assertTrue(back.text, back.text.contains("other words"))
    }

    @Test fun aFileWrittenTwiceIsTheSameFile() {
        val once = SongFile.toFile(song)
        assertEquals(once, SongFile.toFile(SongFile.fromFile(once, "x.cho")!!))
    }

    @Test fun otherProgramsSeeAnOrdinaryChordProFile() {
        val text = SongFile.toFile(song)
        assertTrue(text, text.contains("{title: Lantern Road}\n{artist: Ada Whitlock}\n{capo: 2}\n{key: G}\n"))
        assertTrue(text, text.contains("[G]Lantern on the [C]gatepost"))
        assertTrue(text, text.contains("{start_of_tab}\ne|-3-------0-------|\nB|---0-------1-----|\n{end_of_tab}"))
        // The chords are written as the sheet has them; the reader's transposing rides beside.
        assertTrue(text, text.contains("{x_songbook_transpose: -2}"))
    }

    @Test fun aFileFromElsewhereTakesItsNameForATitle() {
        val s = SongFile.fromFile("[Am]Folded up the [F]morning news\n", "Paper Boats.pro")!!
        assertEquals("Paper Boats", s.title)
        assertEquals("", s.id)
        assertNull(SongFile.fromFile("", ".cho"))
    }

    @Test fun sameLooksPastIdsStampsAndSpacing() {
        assertTrue(SongFile.same(song, song.copy(id = "zz", modified = "later", created = "")))
        assertTrue(SongFile.same(song.copy(text = "G    C\nla la la\n"), song.copy(text = "G    C\nla la la\n\n\n")))
        assertFalse(SongFile.same(song, song.copy(capo = 3)))
        assertFalse(SongFile.same(song, song.copy(transpose = 0)))
    }

    @Test fun namesAFile() {
        assertEquals("Lantern Road", SongFile.fileName(song))
        assertEquals("Ada Whitlock", SongFile.folderName(song))
        assertEquals("Ada Whitlock/Lantern Road", SongFile.place(song))
        assertEquals("Lantern Road", SongFile.place(song.copy(artist = "")))
        assertEquals("AC_DC/What_", SongFile.place(song.copy(title = "What?", artist = "AC/DC")))
        assertEquals("Song", SongFile.fileName(song.copy(title = "", artist = "")))
        assertEquals("_hidden", SongFile.fileName(song.copy(title = ".hidden", artist = "")))
        assertEquals("Lantern Road - Ada Whitlock", SongFile.oldName(song))
        assertEquals("Ada Whitlock", SongFile.fromFile("{title: X}\n[G]la\n", "X.cho", "Ada Whitlock")!!.artist)
        assertEquals("Tomas Reyes", SongFile.fromFile("{title: X}\n{artist: Tomas Reyes}\n[G]la\n", "X.cho", "Ada Whitlock")!!.artist)
        assertEquals("A (2)", SongFile.unique("A", listOf("a", "B")))
        assertEquals("A", SongFile.unique("A", listOf("B")))
        assertTrue(SongFile.isSong("x.CHO") && SongFile.isSong("x.chordpro") && !SongFile.isSong("x.txt"))
    }

    @Test fun readsAFolderListing() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:response><d:href>/remote.php/dav/files/ada/Songbook/</d:href><d:propstat><d:prop><d:getetag>"r1"</d:getetag><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>10</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/files/ada/Songbook/Lantern%20Road%20-%20Ada%20Whitlock.cho</d:href><d:propstat><d:prop><d:getetag>"f1"</d:getetag><d:resourcetype/><oc:fileid>11</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val entries = parseMultistatus(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(2, entries.size)
        assertTrue(entries[0].isFolder)
        assertEquals(Entry("/remote.php/dav/files/ada/Songbook/Lantern Road - Ada Whitlock.cho", "\"f1\"", false, "11"), entries[1])
    }
}
