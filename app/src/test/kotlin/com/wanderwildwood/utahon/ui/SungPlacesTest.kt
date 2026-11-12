package com.wanderwildwood.utahon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SungPlacesTest {

    private fun places(vararg p: SungPiece, width: Int = 200) = sungPlaces(p.toList(), width, space = 5, between = 8)

    @Test fun wordsRunOnWithASpaceBetween() {
        val at = places(SungPiece(0, 40, -1), SungPiece(1, 30, -1), SungPiece(2, 20, -1))
        assertEquals(listOf(0, 45, 80), at.map { it.textX })
        assertEquals(listOf(0, 0, 0), at.map { it.line })
    }

    @Test fun aChordWiderThanItsSyllableReachesOverTheNextWord() {
        // "it" is 10 wide under a chord 30 wide; "seemed" follows at its usual place, under the chord's end.
        val at = places(SungPiece(0, 10, 30), SungPiece(1, 50, -1))
        assertEquals(listOf(0, 15), at.map { it.textX })
        assertEquals(0, at[0].chordX)
    }

    @Test fun twoChordsCloseTogetherAreKeptApart() {
        // The second word has a chord too: it waits until the first chord has ended.
        val at = places(SungPiece(0, 10, 30), SungPiece(1, 50, 20))
        assertEquals(38, at[1].textX)
        assertEquals(38, at[1].chordX)
        // Inside one word the same: the second piece moves, and only as far as it must.
        val word = places(SungPiece(0, 10, 30), SungPiece(0, 50, 20))
        assertEquals(38, word[1].textX)
    }

    @Test fun aWordThatDoesNotFitStartsTheNextLine() {
        val at = places(SungPiece(0, 120, -1), SungPiece(1, 90, -1), SungPiece(2, 60, 20), width = 200)
        assertEquals(listOf(0, 1, 1), at.map { it.line })
        assertEquals(listOf(0, 0, 95), at.map { it.textX })
    }

    @Test fun aWordIsNeverBrokenAcrossLines() {
        val at = places(SungPiece(0, 150, -1), SungPiece(1, 40, 10), SungPiece(1, 40, 10), width = 200)
        assertEquals(listOf(0, 1, 1), at.map { it.line })
    }

    @Test fun aChordThatWouldRunOffTheEdgeTakesItsWordDown() {
        val at = places(SungPiece(0, 170, -1), SungPiece(1, 10, 60), width = 200)
        assertEquals(1, at[1].line)
        assertEquals(0, at[1].chordX)
    }
}
