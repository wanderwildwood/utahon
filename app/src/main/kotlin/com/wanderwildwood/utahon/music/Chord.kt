package com.wanderwildwood.utahon.music

/**
 * A chord as a song sheet names it: a root, what kind of chord it is, and perhaps a bass note
 * after a slash ("D/F#"). The name is kept as written; [tones] says which notes it asks for.
 */
data class Chord(val root: String, val quality: String, val bass: String?) {

    val name: String get() = root + quality + (bass?.let { "/$it" } ?: "")

    /** The root as a pitch class, C = 0. */
    val rootPc: Int get() = pitchClass(root)

    /** The bass note as a pitch class, when the chord names one. */
    val bassPc: Int? get() = bass?.let(::pitchClass)

    /** The chord moved up by [semitones] (down when negative), spelled with flats when [flats]. */
    fun transposed(semitones: Int, flats: Boolean): Chord =
        if (semitones % 12 == 0) this
        else Chord(noteName(rootPc + semitones, flats), quality, bass?.let { noteName(pitchClass(it) + semitones, flats) })

    /** The notes of the chord, as steps above the root. */
    val tones: Tones get() = tonesOf(quality)

    companion object {
        private val NOTE = "[A-G](?:#|b|♯|♭)?"

        // What may follow the root: m, maj7, sus4, add9, 7b5, (b9), 6/9, °, ø, + and the like.
        private val QUALITY = "(?:maj|min|dim|aug|sus|add|M|m|\\+|-|°|ø|º|[0-9]|#|b|\\(|\\)|/9)*"
        private val SHAPE = Regex("^($NOTE)($QUALITY)(?:/($NOTE))?$")

        /** The chord [text] names, or null when it is not a chord's name. */
        fun parse(text: String): Chord? {
            val m = SHAPE.matchEntire(text.trim()) ?: return null
            val quality = m.groupValues[2]
            // "Ab" is A flat, never an A chord of kind "b"; a kind cannot open with a bare b or #
            // unless a number follows it (as in "C#5" read the other way, which the root takes).
            if (quality.startsWith("b") || quality.startsWith("#")) return null
            return Chord(plain(m.groupValues[1]), quality, m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::plain))
        }

        private fun plain(note: String) = note.replace('♯', '#').replace('♭', 'b')
    }
}

private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** A note name as a pitch class, C = 0. */
fun pitchClass(note: String): Int {
    val base = when (note[0]) {
        'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; else -> 11
    }
    val bend = when (note.getOrNull(1)) {
        '#', '♯' -> 1; 'b', '♭' -> -1; else -> 0
    }
    return Math.floorMod(base + bend, 12)
}

/** The name of a pitch class, with a flat or a sharp where it needs one. */
fun noteName(pc: Int, flats: Boolean): String = (if (flats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pc, 12)]

/**
 * The notes a kind of chord is made of, as steps above the root: those it cannot do without
 * ([needed]) and those a fingering may leave out ([optional], the fifth of a seventh chord or
 * of any chord with more notes than three).
 */
data class Tones(val needed: Set<Int>, val optional: Set<Int>) {
    val all: Set<Int> get() = needed + optional
}

/** Reads a chord's kind ("m7", "sus4", "maj9", "7b5") into its notes. */
fun tonesOf(quality: String): Tones {
    var third: Int? = 4
    var fifth: Int? = 7
    val extra = sortedSetOf<Int>()
    var major7 = false
    var q = quality.replace("(", "").replace(")", "").replace("º", "°")
    if (q.startsWith("6/9")) { extra += 9; extra += 2; q = q.substring(3) }

    fun take(vararg words: String): String? {
        val w = words.firstOrNull { q.startsWith(it) } ?: return null
        q = q.substring(w.length)
        return w
    }

    while (q.isNotEmpty()) {
        when {
            take("maj", "M") != null -> major7 = true
            take("min", "m", "-") != null -> third = 3
            take("dim", "°") != null -> { third = 3; fifth = 6; if (q.startsWith("7")) { q = q.substring(1); extra += 9 } }
            take("ø") != null -> { third = 3; fifth = 6; extra += 10; if (q.startsWith("7")) q = q.substring(1) }
            take("aug", "+") != null -> fifth = 8
            take("sus2") != null -> third = 2
            take("sus4", "sus") != null -> third = 5
            take("add9", "add2") != null -> extra += 2
            take("add11", "add4") != null -> extra += 5
            take("add13", "add6") != null -> extra += 9
            take("/9") != null -> extra += 2
            take("add") != null -> Unit
            take("b5") != null -> fifth = 6
            take("#5") != null -> fifth = 8
            take("b9") != null -> extra += 1
            take("#9") != null -> extra += 3
            take("#11") != null -> extra += 6
            take("b13") != null -> extra += 8
            take("13") != null -> { extra += if (major7) 11 else 10; extra += 9 }
            take("11") != null -> { extra += if (major7) 11 else 10; extra += 5 }
            take("9") != null -> { extra += if (major7) 11 else 10; extra += 2 }
            take("7") != null -> extra += if (major7) 11 else 10
            take("6") != null -> extra += 9
            take("5") != null -> if (third == 4 && extra.isEmpty()) third = null
            take("4") != null -> third = 5
            take("2") != null -> third = 2
            else -> q = q.substring(1)
        }
    }
    // "Cmaj" alone is a plain major chord; the seventh comes only with a number.
    val needed = sortedSetOf(0)
    third?.let { needed += it }
    needed += extra
    val optional = sortedSetOf<Int>()
    // A plain fifth can be left out once the chord has a third and something more: the usual
    // C7 (x32310) has none.
    fifth?.let { if (it == 7 && needed.size >= 3) optional += it else needed += it }
    return Tones(needed, optional)
}
