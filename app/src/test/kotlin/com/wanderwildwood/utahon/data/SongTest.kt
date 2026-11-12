package com.wanderwildwood.utahon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTest {

    private val song = Song(
        id = "a1", title = "Lantern Road", artist = "Ada Whitlock", text = "C  Am\nLantern on the gatepost\n",
        kind = "Chords", capo = 2, tuning = "D A D G B E", key = "C", url = "https://example.com/lantern",
        transpose = -3, speed = 7, created = "2026-11-11T10:00:00-05:00", modified = "2026-11-11T10:05:00-05:00",
    )

    @Test fun aSongComesBackFromItsFileAsItWent() {
        assertEquals(listOf(song), Song.allIn(song.toJson().toString()))
    }

    @Test fun aSongbookHoldsMany() {
        val other = Song(id = "b2", title = "Second", text = "G\nla\n")
        assertEquals(listOf(song, other), Song.allIn(songbookDocument(listOf(song, other)).toString(1)))
    }

    @Test fun whatIsNotASongIsNothing() {
        assertTrue(Song.allIn("not json").isEmpty())
        assertTrue(Song.allIn("{\"songs\":[{}]}").isEmpty())
        assertTrue(Song.allIn("{}").isEmpty())
    }

    @Test fun oddNumbersAreKeptInRange() {
        val s = Song.allIn("{\"title\":\"x\",\"text\":\"y\",\"capo\":99,\"transpose\":40}").single()
        assertEquals(24, s.capo)
        assertEquals(11, s.transpose)
    }
}
