package com.wanderwildwood.utahon.music

/**
 * An instrument a chord can be fingered on: its open strings as MIDI notes, in the order a chord
 * box draws them, left to right. [bassFirst] is true where the lowest string sounding should be
 * the chord's root (a guitar); a ukulele's fourth string is tuned high, so there it is not.
 */
enum class Instrument(val id: String, val strings: List<Int>, val bassFirst: Boolean, val mayMute: Int) {
    GUITAR("guitar", listOf(40, 45, 50, 55, 59, 64), bassFirst = true, mayMute = 2),
    UKULELE("ukulele", listOf(67, 60, 64, 69), bassFirst = false, mayMute = 0),
    MANDOLIN("mandolin", listOf(55, 62, 69, 76), bassFirst = false, mayMute = 0);

    companion object {
        fun of(id: String?): Instrument = entries.firstOrNull { it.id == id } ?: GUITAR

        /**
         * The instrument a song's chord boxes are drawn for: a sheet that says it is for the
         * ukulele or the mandolin gets its own, whatever the setting; any other follows [chosen].
         */
        fun forKind(kind: String, chosen: Instrument): Instrument = when {
            kind.contains("ukulele", ignoreCase = true) -> UKULELE
            kind.contains("mandolin", ignoreCase = true) -> MANDOLIN
            else -> chosen
        }
    }
}

/**
 * One way to finger a chord: the fret pressed on each string, 0 for an open string and -1 for a
 * string not played, in the instrument's string order.
 */
data class Voicing(val frets: List<Int>) {
    /** The lowest fret a finger presses, or 0 when every string is open or silent. */
    val lowest: Int get() = frets.filter { it > 0 }.minOrNull() ?: 0
    val highest: Int get() = frets.maxOrNull()?.coerceAtLeast(0) ?: 0

    /** The fret the box starts at: 1 while the shape fits in the first four frets. */
    val firstFret: Int get() = if (highest <= 4) 1 else lowest

    /** As guitarists write it: x32010. Frets past nine are set apart with dashes. */
    override fun toString(): String {
        val wide = frets.any { it > 9 }
        return frets.joinToString(if (wide) "-" else "") { if (it < 0) "x" else it.toString() }
    }

    companion object {
        /** A shape from its written form, "x32010"; one character a string. */
        fun of(text: String): Voicing = Voicing(text.map { if (it == 'x') -1 else it.digitToInt(36) })
    }
}

/**
 * Finds fingerings for a chord on an instrument. The common guitar chords come from the shapes
 * every chord chart prints ([GUITAR_SHAPES]); everything else, and every other instrument, is
 * worked out: each way of pressing strings within four frets that sounds the chord's notes and
 * no others, ranked so the easiest and lowest come first.
 */
object Voicings {

    /** Up to [limit] fingerings of [chord], the likeliest first; empty when none can be made. */
    fun of(chord: Chord, instrument: Instrument, limit: Int = 4): List<Voicing> {
        val found = solve(chord, instrument)
        val known = if (instrument == Instrument.GUITAR) guitarShape(chord) else null
        val all = if (known != null) listOf(known) + found.filter { it != known } else found
        return all.take(limit)
    }

    private fun guitarShape(chord: Chord): Voicing? {
        val key = "${chord.rootPc}:${chord.quality}" + (chord.bassPc?.let { "/$it" } ?: "")
        return GUITAR_SHAPES[key]
    }

    /** Whether [voicing] sounds [chord] on [instrument]: its needed notes, and nothing outside it. */
    fun sounds(voicing: Voicing, chord: Chord, instrument: Instrument): Boolean {
        val tones = chord.tones
        val allowed = (tones.all.map { (chord.rootPc + it) % 12 } + listOfNotNull(chord.bassPc)).toSet()
        val heard = voicing.frets.mapIndexedNotNull { s, f -> if (f < 0) null else (instrument.strings[s] + f) % 12 }
        return heard.all { it in allowed } && tones.needed.all { (chord.rootPc + it) % 12 in heard }
    }

    private class Scored(val voicing: Voicing, val cost: Double)

    private fun solve(chord: Chord, instrument: Instrument): List<Voicing> {
        val tones = chord.tones
        val root = chord.rootPc
        val allowed = (tones.all.map { (root + it) % 12 } + listOfNotNull(chord.bassPc)).toSet()
        val needed = tones.needed.map { (root + it) % 12 }.toSet()
        val bass = chord.bassPc ?: root
        val n = instrument.strings.size
        val out = HashMap<Voicing, Double>()
        // A power chord is two notes, played on the low strings with the high ones left silent.
        val top = if (instrument.mayMute > 0 && tones.all.size <= 2) 3 else 0

        for (base in 1..12) {
            // What each string may do in this position: stay silent (the low strings only), ring
            // open, or be pressed on one of four frets.
            val choices = List(n) { s ->
                val list = ArrayList<Int>()
                if (s < instrument.mayMute || s >= n - top) list += -1
                if ((instrument.strings[s]) % 12 in allowed) list += 0
                for (f in base..base + 3) if ((instrument.strings[s] + f) % 12 in allowed) list += f
                list
            }
            if (choices.any { it.isEmpty() }) continue
            val frets = IntArray(n)
            fun walk(s: Int) {
                if (s == n) {
                    score(frets, instrument, needed, tones, root, bass, if (top > 0) 2 else minOf(n, 4))?.let { cost ->
                        val v = Voicing(frets.toList())
                        val old = out[v]
                        if (old == null || cost < old) out[v] = cost
                    }
                    return
                }
                for (f in choices[s]) {
                    // Silent strings are the lowest ones, side by side, or (in a power chord) the
                    // highest: once a high string is left out, so is every string above it.
                    val low = s == 0 || (s < instrument.mayMute && frets[s - 1] == -1)
                    if (f == -1 && !low && s < n - top) continue
                    if (f != -1 && s > 0 && frets[s - 1] == -1 && (0 until s).any { frets[it] != -1 }) continue
                    frets[s] = f
                    walk(s + 1)
                }
            }
            walk(0)
        }
        return out.entries.map { Scored(it.key, it.value) }
            .sortedWith(compareBy<Scored> { it.cost }.thenBy { it.voicing.toString() })
            .map { it.voicing }
    }

    /** How hard and how far up the neck a shape is, or null when a hand cannot make it. */
    private fun score(frets: IntArray, instrument: Instrument, needed: Set<Int>, tones: Tones, root: Int, bass: Int, atLeast: Int): Double? {
        val sounding = frets.indices.filter { frets[it] >= 0 }
        if (sounding.size < atLeast) return null
        val heard = sounding.map { (instrument.strings[it] + frets[it]) % 12 }
        if (!heard.containsAll(needed)) return null
        if (instrument.bassFirst && heard.first() != bass) return null

        val pressed = sounding.filter { frets[it] > 0 }
        val low = pressed.minOfOrNull { frets[it] } ?: 0
        // One finger laid across the lowest fret counts once, so long as no open string lies under it.
        val atLow = pressed.filter { frets[it] == low }
        val barre = atLow.size >= 2 && sounding.none { frets[it] == 0 && it > atLow.first() }
        val fingers = if (barre) 1 + pressed.count { frets[it] > low } else pressed.size
        if (fingers > 4) return null

        val open = sounding.count { frets[it] == 0 }
        val silent = frets.size - sounding.size
        val fifth = (root + 7) % 12
        val thin = if (7 in tones.optional && fifth !in heard) 1.5 else 0.0
        return low * 2.0 + silent * 2.5 + fingers + (if (barre) 1.0 else 0.0) - open * 0.5 + thin
    }

    private fun shapes(vararg pairs: Pair<String, String>): Map<String, Voicing> = pairs.associate { (name, shape) ->
        val chord = requireNotNull(Chord.parse(name)) { name }
        ("${chord.rootPc}:${chord.quality}" + (chord.bassPc?.let { "/$it" } ?: "")) to Voicing.of(shape)
    }

    /** The guitar chords a chart prints, in standard tuning, low E first. */
    internal val GUITAR_SHAPES: Map<String, Voicing> = shapes(
        "C" to "x32010", "D" to "xx0232", "E" to "022100", "F" to "133211", "G" to "320003", "A" to "x02220", "B" to "x24442",
        "C#" to "x46664", "Eb" to "x68886", "F#" to "244322", "Ab" to "466544", "Bb" to "x13331",
        "Cm" to "x35543", "Dm" to "xx0231", "Em" to "022000", "Fm" to "133111", "Gm" to "355333", "Am" to "x02210", "Bm" to "x24432",
        "C#m" to "x46654", "Ebm" to "x68876", "F#m" to "244222", "G#m" to "466444", "Bbm" to "x13321",
        "C7" to "x32310", "D7" to "xx0212", "E7" to "020100", "F7" to "131211", "G7" to "320001", "A7" to "x02020", "B7" to "x21202",
        "Cmaj7" to "x32000", "Dmaj7" to "xx0222", "Emaj7" to "021100", "Fmaj7" to "xx3210", "Gmaj7" to "320002", "Amaj7" to "x02120",
        "Dm7" to "xx0211", "Em7" to "022030", "Am7" to "x02010", "Bm7" to "x24232", "F#m7" to "242222",
        "Dsus4" to "xx0233", "Dsus2" to "xx0230", "Asus4" to "x02230", "Asus2" to "x02200", "Esus4" to "022200",
        "Gsus4" to "330013", "Csus4" to "x33011", "Cadd9" to "x32030", "Gadd9" to "320203",
        "G/B" to "x20003", "D/F#" to "200232", "C/G" to "332010", "C/B" to "x22010", "Am/G" to "302210",
    )
}
