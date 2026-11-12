package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordProTest {

    private val file = """
        {title: Lantern Road}
        {artist: Ada Whitlock}
        {capo: 2}
        {key: G}
        # a note to myself
        {start_of_verse: Verse 1}
        [G]Lantern on the [C]gatepost
        Shows the [G]road is [D]long
        {end_of_verse}
        {soc}
        [C]Carry me [G]on
        {eoc}
        {comment: Outro}
        [G] [C] [G]
        {sot}
        e|-3-------0-------|
        {eot}
    """.trimIndent()

    @Test fun readsAFile() {
        val r = ChordPro.read(file)
        assertEquals("Lantern Road", r.title)
        assertEquals("Ada Whitlock", r.artist)
        assertEquals(2, r.capo)
        assertEquals("G", r.key)
        assertEquals(
            """
            [Verse 1]
            G              C
            Lantern on the gatepost
                      G       D
            Shows the road is long

            [Chorus]
            C        G
            Carry me on

            [Outro]
            G  C  G
            e|-3-------0-------|
            """.trimIndent() + "\n",
            r.text,
        )
        val lines = Sheet.read(r.text)
        assertEquals(3, lines.count { it is SheetLine.Sung })
        assertEquals(1, lines.count { it is SheetLine.Tab })
        assertEquals(listOf("G", "C", "D"), Sheet.chords(lines).map { it.name })
    }

    @Test fun tellsChordProFromAPlainSheet() {
        assertTrue(ChordPro.looksLike(file))
        assertTrue(ChordPro.looksLike("[Am]Folded up the [F]morning news"))
        assertTrue(ChordPro.looksLike("{t: A song}\nwords"))
        assertFalse(ChordPro.looksLike("[Verse]\nAm       F\nFolded up the morning news"))
        assertFalse(ChordPro.looksLike("[Am]\nwords under a bracketed chord"))
        assertFalse(ChordPro.looksLike("Play it slowly {like this}"))
    }

    @Test fun chordsCloseTogetherKeepTheirSyllables() {
        assertEquals(listOf("C G", "  la"), ChordPro.over("[C][G]la"))
        assertEquals(listOf("Am7 D", "a   long way"), ChordPro.over("[Am7]a [D]long way"))
        assertEquals(listOf("no chords here"), ChordPro.over("no chords here"))
        assertEquals(listOf("see [the note]"), ChordPro.over("see [the note]"))
    }

    @Test fun writesAFileThatReadsBackTheSame() {
        val sheet = "[Verse 1]\nG              C\nLantern on the gatepost\n\nG  C  G  (x2)\ne|-3-------0-------|\nPlay it slowly.\n"
        val out = ChordPro.write("Lantern Road", "Ada Whitlock", 2, "G", Sheet.read(sheet), 0)
        assertEquals(
            "{title: Lantern Road}\n{artist: Ada Whitlock}\n{capo: 2}\n{key: G}\n\n{comment: Verse 1}\n[G]Lantern on the [C]gatepost\n\n" +
                "[G] [C] [G] (x2)\n{start_of_tab}\ne|-3-------0-------|\n{end_of_tab}\nPlay it slowly.\n",
            out,
        )
        val back = ChordPro.read(out)
        assertEquals("Lantern Road", back.title)
        assertEquals(2, back.capo)
        assertEquals(Sheet.chords(Sheet.read(sheet)).map { it.name }, Sheet.chords(Sheet.read(back.text)).map { it.name })
        assertTrue(back.text, back.text.contains("G              C\nLantern on the gatepost"))
        assertTrue(back.text, back.text.startsWith("[Verse 1]\n"))
    }

    @Test fun writesTheChordsMoved() {
        val out = ChordPro.write("", "", 0, "G", Sheet.read("G    C\nLantern road\n"), 2)
        assertEquals("[A]Lante[D]rn road\n", out)
    }

    @Test fun aChordPastTheWordsIsKept() {
        val out = ChordPro.write("", "", 0, "", Sheet.read("G        D\nHome\n"), 0)
        assertEquals("[G]Home [D]\n", out)
    }
}
