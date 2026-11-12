package com.wanderwildwood.utahon.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicingsTest {

    private val roots = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val kinds = listOf("", "m", "7", "m7", "maj7", "sus4", "sus2", "6", "dim", "aug", "9", "add9", "5", "m7b5")

    @Test fun thePrintedGuitarShapesSoundTheirChords() {
        val names = listOf(
            "C", "D", "E", "F", "G", "A", "B", "C#", "Eb", "F#", "Ab", "Bb",
            "Cm", "Dm", "Em", "Fm", "Gm", "Am", "Bm", "C#m", "Ebm", "F#m", "G#m", "Bbm",
            "C7", "D7", "E7", "F7", "G7", "A7", "B7", "Cmaj7", "Dmaj7", "Emaj7", "Fmaj7", "Gmaj7", "Amaj7",
            "Dm7", "Em7", "Am7", "Bm7", "F#m7", "Dsus4", "Dsus2", "Asus4", "Asus2", "Esus4", "Gsus4", "Csus4",
            "Cadd9", "Gadd9", "G/B", "D/F#", "C/G", "C/B", "Am/G",
        )
        assertEquals(names.size, Voicings.GUITAR_SHAPES.size)
        for (name in names) {
            val chord = Chord.parse(name)!!
            val shape = Voicings.of(chord, Instrument.GUITAR).first()
            assertTrue("$name $shape", Voicings.sounds(shape, chord, Instrument.GUITAR))
        }
    }

    @Test fun theOpenChordsComeFirst() {
        fun first(name: String, on: Instrument = Instrument.GUITAR) = Voicings.of(Chord.parse(name)!!, on).first().toString()
        assertEquals("x32010", first("C"))
        assertEquals("320003", first("G"))
        assertEquals("xx0232", first("D"))
        assertEquals("022000", first("Em"))
        // A flat spelling finds the same shape as its sharp.
        assertEquals(first("C#m"), first("Dbm"))
        assertEquals("0003", first("C", Instrument.UKULELE))
        assertEquals("2010", first("F", Instrument.UKULELE))
        assertEquals("0232", first("G", Instrument.UKULELE))
        assertEquals("2000", first("Am", Instrument.UKULELE))
    }

    @Test fun everyCommonChordHasAShapeOnEveryInstrument() {
        for (instrument in Instrument.entries) for (root in roots) for (kind in kinds) {
            val chord = Chord.parse(root + kind)!!
            val shapes = Voicings.of(chord, instrument)
            assertTrue("no shape for ${chord.name} on $instrument", shapes.isNotEmpty())
            for (shape in shapes) {
                assertTrue("${chord.name} $shape on $instrument", Voicings.sounds(shape, chord, instrument))
                assertEquals(instrument.strings.size, shape.frets.size)
                val pressed = shape.frets.filter { it > 0 }
                if (pressed.isNotEmpty()) assertTrue("${chord.name} $shape stretches", pressed.max() - pressed.min() <= 3)
            }
        }
    }

    @Test fun aWorkedOutGuitarShapeHasItsRootInTheBass() {
        for (name in listOf("F#7", "Bbm7", "Ab6", "C#sus4", "Ebmaj7")) {
            val chord = Chord.parse(name)!!
            for (shape in Voicings.of(chord, Instrument.GUITAR)) {
                val lowest = shape.frets.indexOfFirst { it >= 0 }
                assertEquals("$name $shape", chord.rootPc, (Instrument.GUITAR.strings[lowest] + shape.frets[lowest]) % 12)
            }
        }
    }

    @Test fun aPowerChordLeavesTheHighStringsOut() {
        assertEquals("x466xx", Voicings.of(Chord.parse("C#5")!!, Instrument.GUITAR).first().toString())
        // Where open strings carry the chord, they ring.
        assertEquals("022400", Voicings.of(Chord.parse("E5")!!, Instrument.GUITAR).first().toString())
    }

    @Test fun writesShapesAsGuitaristsDo() {
        assertEquals("x32010", Voicing(listOf(-1, 3, 2, 0, 1, 0)).toString())
        assertEquals("x-10-12-12-12-10", Voicing(listOf(-1, 10, 12, 12, 12, 10)).toString())
        assertEquals(1, Voicing.of("x32010").firstFret)
        assertEquals(6, Voicing.of("x68886").firstFret)
    }
}
