package com.wanderwildwood.utahon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetlistTest {

    @Test fun movesASong() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), Setlists.moved(l, 0, 1))
        assertEquals(listOf("a", "d", "b", "c"), Setlists.moved(l, 3, 1))
        assertEquals(l, Setlists.moved(l, 2, 2))
        assertEquals(l, Setlists.moved(l, 0, 9))
    }

    @Test fun aSongbookCarriesItsSetlists() {
        val song = Song(id = "a1", title = "Lantern Road", text = "G\nla\n")
        val list = Setlist("s1", "Friday", listOf("a1", "b2"))
        val doc = songbookDocument(listOf(song), listOf(list)).toString(1)
        assertEquals(listOf(list), Setlist.allIn(doc))
        assertEquals(listOf(song), Song.allIn(doc))
        assertTrue(Setlist.allIn(songbookDocument(listOf(song)).toString()).isEmpty())
        assertTrue(Setlist.allIn("not json").isEmpty())
    }

    @Test fun picksTheSpeedNearestAWait() {
        assertEquals(5, com.wanderwildwood.utahon.ui.speedFor(12))
        assertEquals(3, com.wanderwildwood.utahon.ui.speedFor(20))
        // A step is a third of the page, two rows at the least, never all of it.
        assertEquals(listOf(1, 1, 2, 2, 2, 3, 4), listOf(1, 2, 3, 5, 7, 9, 12).map { com.wanderwildwood.utahon.ui.stride(it) })
        assertEquals(1, com.wanderwildwood.utahon.ui.speedFor(500))
        assertEquals(9, com.wanderwildwood.utahon.ui.speedFor(2))
        assertEquals(9, com.wanderwildwood.utahon.ui.SPEED_SECONDS.size)
    }

    @Test fun aLineWaitsItsShareOfTheThird() {
        // 12 s for a third of a 600 px page: a 50 px line gets a quarter of that, a row a third of the page tall all of it.
        assertEquals(3000L, com.wanderwildwood.utahon.ui.lineWait(12, 50, 600))
        assertEquals(12000L, com.wanderwildwood.utahon.ui.lineWait(12, 200, 600))
        // A head taller than that waits no longer than one step did; a sliver still waits long enough to be seen.
        assertEquals(12000L, com.wanderwildwood.utahon.ui.lineWait(12, 500, 600))
        assertEquals(400L, com.wanderwildwood.utahon.ui.lineWait(3, 10, 600))
        assertEquals(5000L, com.wanderwildwood.utahon.ui.lineWait(5, 40, 0))
    }

    @Test fun readsALengthAsItIsTyped() {
        assertEquals(220, lengthFrom("3:40"))
        assertEquals(95, lengthFrom("95"))
        assertEquals(0, lengthFrom(""))
        assertEquals(0, lengthFrom("a:b"))
        assertEquals("3:40", lengthText(220))
        assertEquals("0:05", lengthText(5))
        assertEquals("", lengthText(0))
        val s = Song(id = "x", title = "t", text = "y", length = 220)
        assertEquals(listOf(s), Song.allIn(s.toJson().toString()))
    }
}
