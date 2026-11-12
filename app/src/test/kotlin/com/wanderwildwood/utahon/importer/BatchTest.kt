package com.wanderwildwood.utahon.importer

import com.wanderwildwood.utahon.music.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTest {

    @Test fun findsEveryAddressOnce() {
        val text = "https://tabs.example.com/tab/a-1\nhttps://tabs.example.com/tab/b-2, https://tabs.example.com/tab/a-1\n"
        assertEquals(listOf("https://tabs.example.com/tab/a-1", "https://tabs.example.com/tab/b-2"), Reader.addressesIn(text))
        assertTrue(Reader.addressesIn("no links here").isEmpty())
    }

    @Test fun tellsAListOfLinksFromASheetOrOneLink() {
        assertTrue(Reader.isLinkList("https://example.com/a\nhttps://example.com/b"))
        assertTrue(Reader.isLinkList("two for later: https://example.com/a and https://example.com/b"))
        assertFalse(Reader.isLinkList("https://example.com/a"))
        assertFalse(Reader.isLinkList("C  Am\nLantern on the gatepost shows the road is long tonight\nfrom https://example.com/a or https://example.com/b"))
    }

    @Test fun aSheetForTheUkuleleGetsUkuleleBoxes() {
        assertEquals(Instrument.UKULELE, Instrument.forKind("Ukulele Chords", Instrument.GUITAR))
        assertEquals(Instrument.GUITAR, Instrument.forKind("Chords", Instrument.GUITAR))
        assertEquals(Instrument.MANDOLIN, Instrument.forKind("", Instrument.MANDOLIN))
        assertEquals(Instrument.UKULELE, Instrument.forKind("Tab", Instrument.UKULELE))
    }
}
