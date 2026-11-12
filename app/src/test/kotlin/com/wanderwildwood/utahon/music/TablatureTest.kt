package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TablatureTest {

    @Test fun everyFretGoesUpWithTheSong() {
        val rows = listOf("e|-3-------0-------|", "B|---0-------1-----|", "G|-----0-------0---|")
        assertEquals(
            listOf("e|-5-------2-------|", "B|---2-------3-----|", "G|-----2-------2---|"),
            Tablature.moved(rows, 2),
        )
        // Unmoved, it is the same tablature.
        assertSame(rows, Tablature.moved(rows, 0))
    }

    @Test fun aNumberThatGrowsTakesADashToSpare() {
        // Nine to ten: the dash after it goes, and the 7 and 8 further on stay over each other.
        assertEquals(listOf("e|-10--8-|", "B|-----9-|"), Tablature.moved(listOf("e|-9---7-|", "B|-----8-|"), 1))
    }

    @Test fun withNoDashToSpareEveryStringGetsAColumn() {
        val moved = Tablature.moved(listOf("e|-9-7-|", "B|-8-9-|", "G|-----|"), 2)
        assertEquals(listOf("e|-11-9--|", "B|-10-11-|", "G|-------|"), moved)
        assertEquals(1, moved.map { it.length }.distinct().size)
    }

    @Test fun aNumberThatShrinksLeavesADash() {
        assertEquals(listOf("e|-7--9--|", "B|--7h9--|"), Tablature.moved(listOf("e|-10-12-|", "B|-10h12-|"), -3))
    }

    @Test fun movesDownWhileNoFretGoesBelowTheOpenString() {
        assertEquals(listOf("e|-3-1-|"), Tablature.moved(listOf("e|-5-3-|"), -2))
    }

    @Test fun aMoveBelowTheOpenStringGoesUpAnOctaveInstead() {
        // Down two would ask for fret -2 and -1: the same notes are ten frets up.
        assertEquals(listOf("e|-13-10-|", "B|-11----|"), Tablature.moved(listOf("e|-3-0-|", "B|-1---|"), -2))
    }

    @Test fun theWholeSongGoesUpTogether() {
        // The second system could go down two as written; the first cannot, so both go up ten.
        val lines = Sheet.read("e|-3---0---3---|\n\nla la la\n\ne|-5---7---5---|")
        assertEquals(10, Tablature.shift(lines, -2))
        assertEquals(-2, Tablature.shift(Sheet.read("e|-5---7---5---|"), -2))
        val moved = Tablature.movedLines(lines, -2)
        assertEquals(listOf("e|-13--10--13--|"), (moved.first() as SheetLine.Tab).rows)
        assertEquals(listOf("e|-15--17--15--|"), (moved.last() as SheetLine.Tab).rows)
    }

    @Test fun barsLettersAndStringNamesStay() {
        assertEquals(listOf("G|--8h10-6/8--x--10b12~--|"), Tablature.moved(listOf("G|--7h9--5/7--x--9b11~--|"), 1))
        // A string named by its number, a slide down, and "x2" past the closing bar.
        assertEquals(listOf("6|--7--10-|  x2", "5|--9\\7---|"), Tablature.moved(listOf("6|--0--3--|  x2", "5|--2\\0---|"), 7))
        // A flat string keeps its name: the b is no bend, and E flat is not moved to F.
        assertEquals(listOf("Eb|-4-6-|"), Tablature.moved(listOf("Eb|-2-4-|"), 2))
    }

    @Test fun aLineThatIsNotTablatureIsLeftAlone() {
        val lines = Sheet.read("Play it 3 times, then 12 more\n\nC  G\n\ne|-3-------0-------|")
        val moved = Tablature.movedLines(lines, 2)
        assertEquals(SheetLine.Words("Play it 3 times, then 12 more"), moved[0])
        assertEquals(lines[2], moved[2])
        assertEquals(listOf("e|-5-------2-------|"), (moved[4] as SheetLine.Tab).rows)
        val plain = Sheet.plain(lines, 2)
        assertTrue(plain, plain.contains("Play it 3 times, then 12 more\n\nD  A\n\ne|-5-------2-------|"))
        // A song with no tablature is the list it was.
        val words = Sheet.read("C  G\nla la")
        assertSame(words, Tablature.movedLines(words, 5))
    }

    @Test fun aChordProFileCarriesTheMovedTablature() {
        val out = ChordPro.write("Lantern Road", "Ada Whitlock", 0, "", Sheet.read("G  C\n\ne|-3-------0-------|"), 2)
        assertTrue(out, out.contains("[A] [D]\n\n{start_of_tab}\ne|-5-------2-------|\n{end_of_tab}"))
    }
}
