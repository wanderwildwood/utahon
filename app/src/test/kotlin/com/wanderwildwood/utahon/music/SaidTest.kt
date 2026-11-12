package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaidTest {

    private fun chords(line: String) = Sheet.said(line)?.marks?.filterIsInstance<Mark.Of>()?.map { it.chord.name }

    @Test fun findsChordsNamedInASentence() {
        assertEquals(listOf("C", "G"), chords("Repeat C and G"))
        assertEquals(listOf("Em7"), chords("Hold the Em7 a little longer"))
        assertEquals(listOf("G", "C", "D"), chords("Intro: G C D"))
        assertEquals(listOf("A", "D"), chords("Play A then D (x2)"))
        assertEquals(listOf("F#m", "A"), chords("(F#m to A, slowly)"))
        assertEquals(listOf("G", "C"), chords("A quick G to C run for the outro"))
    }

    @Test fun wordsStayWords() {
        assertNull(Sheet.said("Play it slowly."))
        assertNull(Sheet.said("Note: this one suits a harmonica in C"))
        assertNull(Sheet.said("Vitamin C and vitamin D"))
        assertNull(Sheet.said("I Am the only one"))
        assertNull(Sheet.said("A long way from home"))
        assertNull(Sheet.said("(Repeat riff x2)"))
    }

    @Test fun keepsThePunctuationAroundAChord() {
        val line = Sheet.said("(F#m to A, slowly)")!!
        assertEquals("(F#m to A, slowly)", Sheet.sentence(line) { it.name })
        assertEquals("(G#m to B, slowly)", Sheet.sentence(line) { it.transposed(2, false).name })
    }

    @Test fun movesWithTheRestOfTheSong() {
        val lines = Sheet.read("C    G\nLantern road\n\nRepeat C and G to end\n")
        assertTrue(lines.last() is SheetLine.Said)
        assertEquals(listOf("C", "G"), Sheet.chords(lines).map { it.name })
        assertTrue(Sheet.plain(lines, 2).endsWith("Repeat D and A to end\n"))
        val pro = ChordPro.write("", "", 0, "", lines, 2)
        assertTrue(pro, pro.endsWith("Repeat D and A to end\n"))
        // Written out unmoved and read back, it is the same sentence.
        assertEquals(lines.last(), Sheet.read(Sheet.plain(lines, 0)).last())
    }
}
