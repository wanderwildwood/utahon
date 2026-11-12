package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTest {

    private val song = """
        [Intro]
        e|-3-------0-------|
        B|---0-------1-----|
        G|-----0-------0---|
        C    Am   G  (x2)

        [Verse 1]
        C              Am
        Lantern on the gatepost
               F           G
        Shows the road is long

        Play it slowly.
    """.trimIndent()

    @Test fun readsTheKindsOfLine() {
        val lines = Sheet.read(song)
        assertEquals(SheetLine.Part("Intro"), lines[0])
        assertEquals(3, (lines[1] as SheetLine.Tab).rows.size)
        val intro = lines[2] as SheetLine.Chords
        assertEquals(listOf("C", "Am", "G", "(x2)"), intro.marks.map { if (it is Mark.Of) it.chord.name else (it as Mark.Word).text })
        assertEquals(SheetLine.Blank, lines[3])
        assertEquals(SheetLine.Part("Verse 1"), lines[4])
        val first = lines[5] as SheetLine.Sung
        assertEquals("Lantern on the gatepost", first.words)
        assertEquals(listOf(0 to "C", 15 to "Am"), first.chords.map { it.column to it.chord.name })
        assertTrue(lines[6] is SheetLine.Sung)
        assertEquals(SheetLine.Blank, lines[7])
        assertEquals(SheetLine.Words("Play it slowly."), lines[8])
        assertEquals(9, lines.size)
    }

    @Test fun aLineOfWordsIsNotChords() {
        assertNull(Sheet.chordLine("A long way from home"))
        assertNull(Sheet.chordLine("Am I the only one"))
        assertFalse(Sheet.isTab("Well -- I never -- did"))
        assertTrue(Sheet.isTab("G|-----0---0-----2p0--|"))
        assertTrue(Sheet.chordLine("| C  G | Am  F |") != null)
    }

    @Test fun aBracketedChordIsNotAPart() {
        assertTrue(Sheet.read("[Am]\nwords").none { it is SheetLine.Part })
    }

    @Test fun cutsWordsWhereChordsFall() {
        val line = Sheet.read("C       G   Am\nLantern on the gatepost").single() as SheetLine.Sung
        val words = Sheet.words(line)
        fun text(w: List<Sheet.Piece>) = w.joinToString("|") { (it.chord?.name ?: "") + ":" + it.text }
        assertEquals(listOf("C:Lantern", "G:on", ":t|Am:he", ":gatepost"), words.map(::text))
    }

    @Test fun chordsPastTheWordsStandAlone() {
        val line = SheetLine.Sung(listOf(Placed(0, Chord.parse("C")!!), Placed(12, Chord.parse("G")!!), Placed(16, Chord.parse("F")!!)), "Home")
        val words = Sheet.words(line)
        assertEquals(3, words.size)
        assertEquals("G", words[1].single().chord!!.name)
        assertEquals("", words[2].single().text)
    }

    @Test fun twoChordsOverAGapKeepTheirOrder() {
        val line = SheetLine.Sung(listOf(Placed(5, Chord.parse("C")!!), Placed(8, Chord.parse("G")!!)), "Home      again")
        val words = Sheet.words(line)
        assertEquals(listOf(null, "C", "G"), words.map { it.first().chord?.name })
        assertEquals("again", words.last().single().text)
    }

    @Test fun listsEachChordOnce() {
        assertEquals(listOf("C", "Am", "G", "F"), Sheet.chords(Sheet.read(song)).map { it.name })
    }

    @Test fun writesTheSongOutMoved() {
        val moved = Sheet.plain(Sheet.read(song), 2)
        assertTrue(moved, moved.contains("D              Bm\nLantern on the gatepost"))
        assertTrue(moved, moved.contains("D  Bm  A  (x2)"))
        // The tablature goes up two frets with the chords.
        assertTrue(moved, moved.contains("e|-5-------2-------|\nB|---2-------3-----|\nG|-----2-------2---|"))
        assertTrue(moved, moved.contains("[Verse 1]"))
        // Unmoved, the chords stand where they stood.
        assertTrue(Sheet.plain(Sheet.read(song), 0).contains("       F           G\nShows the road is long"))
        assertTrue(Sheet.plain(Sheet.read(song), 0).contains("e|-3-------0-------|"))
    }

    @Test fun aLongerNameDoesNotRunIntoTheNext() {
        // A and B a column apart, moved up one: B flat is a letter longer, and C steps aside for it.
        assertEquals("Bb C\nla la\n", Sheet.plain(Sheet.read("A B\nla la"), 1))
    }

    @Test fun picksFlatsForFlatKeys() {
        assertTrue(Sheet.flats(Sheet.read("Bb  Eb  F\nwords here"), 0))
        assertFalse(Sheet.flats(Sheet.read("E  A  B\nwords here"), 0))
        // C moved down two is B flat.
        assertTrue(Sheet.flats(Sheet.read("C  F  G\nwords here"), -2))
        assertFalse(Sheet.flats(Sheet.read("C  F  G\nwords here"), 2))
    }

    @Test fun cutsTabAtTheSameColumnOnEveryString() {
        val rows = listOf("e|-0-1-2-3-4-5-|", "B|-5-4-3-2-1-0-|")
        val cut = Sheet.cut(rows, 8)
        assertEquals(listOf(listOf("e|-0-1-2", "B|-5-4-3"), listOf("-3-4-5-|", "-2-1-0-|")), cut)
        assertEquals(listOf(rows), Sheet.cut(rows, 40))
        // Seventeen characters in rows of ten: two pieces of nine and eight, not ten and a stub.
        assertEquals(listOf(9, 8), Sheet.cut(listOf("e|-0-1-2-3-4-5-6|"), 10).map { it.single().length })
    }
}
