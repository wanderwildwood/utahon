package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChordTest {

    @Test fun readsChordNames() {
        for (name in listOf("C", "Am", "F#m7", "Bb", "Dsus4", "G/B", "Cadd9", "E7", "Abmaj7", "C#dim", "D/F#", "Bm7b5", "A7sus4", "G6", "Em9", "C6/9", "F+", "B°7")) {
            assertNotNull(name, Chord.parse(name))
        }
    }

    @Test fun wordsAreNotChords() {
        for (word in listOf("And", "Add", "Bad", "Go", "Dad", "Be", "Cage", "Face", "", "I", "Hm", "Ebb", "G/", "A/X")) {
            assertNull(word, Chord.parse(word))
        }
    }

    @Test fun keepsRootKindAndBass() {
        assertEquals(Chord("F#", "m7", null), Chord.parse("F#m7"))
        assertEquals(Chord("D", "", "F#"), Chord.parse("D/F#"))
        assertEquals(Chord("Bb", "maj7", null), Chord.parse("B♭maj7"))
        assertEquals("D/F#", Chord.parse("D/F#")!!.name)
    }

    @Test fun movesUpAndDown() {
        fun moved(name: String, by: Int, flats: Boolean = false) = Chord.parse(name)!!.transposed(by, flats).name
        assertEquals("D", moved("C", 2))
        assertEquals("Bm7", moved("Am7", 2))
        assertEquals("A#", moved("C", -2))
        assertEquals("Bb", moved("C", -2, flats = true))
        assertEquals("E/G#", moved("D/F#", 2))
        assertEquals("C", moved("B", 1))
        assertEquals("B", moved("C", -1))
        assertEquals("Gsus4", moved("Gsus4", 12))
        assertEquals("Eb", moved("Eb", 0))
    }

    @Test fun kindsHaveTheirNotes() {
        fun needed(kind: String) = tonesOf(kind).needed.toList()
        assertEquals(listOf(0, 4, 7), needed(""))
        assertEquals(listOf(0, 3, 7), needed("m"))
        assertEquals(listOf(0, 4, 10), needed("7"))
        assertEquals(setOf(7), tonesOf("7").optional)
        assertEquals(listOf(0, 4, 11), needed("maj7"))
        assertEquals(listOf(0, 3, 10), needed("m7"))
        assertEquals(listOf(0, 5, 7), needed("sus4"))
        assertEquals(listOf(0, 2, 7), needed("sus2"))
        assertEquals(listOf(0, 3, 6), needed("dim"))
        assertEquals(listOf(0, 3, 6, 9), needed("dim7"))
        assertEquals(listOf(0, 3, 6, 10), needed("m7b5"))
        assertEquals(listOf(0, 4, 8), needed("aug"))
        assertEquals(listOf(0, 7), needed("5"))
        assertEquals(listOf(0, 4, 9), needed("6"))
        assertEquals(listOf(0, 2, 4), needed("add9"))
        // A seventh or a ninth chord may leave its fifth out; a triad may not.
        assertEquals(listOf(0, 2, 4, 10), needed("9"))
        assertEquals(setOf(7), tonesOf("9").optional)
        assertEquals(listOf(0, 4, 7), needed("maj"))
    }
}
