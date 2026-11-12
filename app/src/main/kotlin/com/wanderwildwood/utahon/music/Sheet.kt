package com.wanderwildwood.utahon.music

/** A chord over a line of words, and how many characters in it stands. */
data class Placed(val column: Int, val chord: Chord)

/** One piece of a line of chords: a chord, or something written among them ("x2", "|", "N.C."). */
sealed interface Mark {
    /** A chord; [before] and [after] are the brackets or punctuation written hard against it. */
    data class Of(val chord: Chord, val before: String = "", val after: String = "") : Mark
    data class Word(val text: String) : Mark
}

/** A line of a song sheet, as the screen draws it. */
sealed interface SheetLine {
    data object Blank : SheetLine

    /** The name of a part: Verse, Chorus, Bridge. */
    data class Part(val name: String) : SheetLine

    /** Chords with no words under them, as in an intro. */
    data class Chords(val marks: List<Mark>) : SheetLine

    /** A line of words with the chords that go over it. */
    data class Sung(val chords: List<Placed>, val words: String) : SheetLine

    /**
     * A sentence with chords named in it ("Repeat C and G", "Intro: Em7 x2"): words and chords in
     * the order they are written, so the chords move with the rest when the song is transposed.
     */
    data class Said(val marks: List<Mark>) : SheetLine

    /** Tablature: the strings of one system, to be kept in step with each other. */
    data class Tab(val rows: List<String>) : SheetLine

    /** Anything else, as written. */
    data class Words(val text: String) : SheetLine
}

/**
 * A song sheet read from plain text, the way people write them: chords on a line of their own
 * over the words they belong to, a part named in square brackets, tablature as rows of dashes.
 * The text itself stays as it was typed or saved; this reading is only for showing it.
 */
object Sheet {

    private val PART = Regex("""^\s*\[([^\[\]]{1,40})]\s*$""")
    private val FILLER = Regex("""^(?:\||\|\||:\||\|:|-+|/+|%|\*+|\(?[xX]\s?\d+\)?|\(?\d+[xX]\)?|N\.?C\.?|\(|\)|,|\.+)$""")
    private val TAB_REST = Regex("""[-0-9|hpbrstvxHPBRSTVX/\\~().<>^=*:\s]""")
    private val TAB_HEAD = Regex("""^\s*[A-Ga-g][#b]?\s*(?=[|:\-])""")
    private val SPACES = Regex("""\s+""")

    fun read(text: String): List<SheetLine> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n').map { it.trimEnd() }
        val out = ArrayList<SheetLine>()
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            val part = PART.matchEntire(line)
            when {
                line.isBlank() -> {
                    // One blank line is a breath; three in a row are still one.
                    if (out.isNotEmpty() && out.last() != SheetLine.Blank) out += SheetLine.Blank
                    i++
                }
                part != null && Chord.parse(part.groupValues[1]) == null -> {
                    out += SheetLine.Part(part.groupValues[1].trim())
                    i++
                }
                isTab(line) -> {
                    val rows = ArrayList<String>()
                    while (i < raw.size && isTab(raw[i])) rows += raw[i++]
                    out += SheetLine.Tab(rows)
                }
                else -> {
                    val chords = chordLine(line)
                    val next = raw.getOrNull(i + 1)
                    if (chords == null) {
                        out += said(line) ?: SheetLine.Words(line)
                        i++
                    } else if (next != null && next.isNotBlank() && PART.matchEntire(next) == null && !isTab(next) && chordLine(next) == null) {
                        out += SheetLine.Sung(chords.second, next)
                        i += 2
                    } else {
                        out += SheetLine.Chords(chords.first)
                        i++
                    }
                }
            }
        }
        while (out.isNotEmpty() && out.last() == SheetLine.Blank) out.removeAt(out.lastIndex)
        return out
    }

    /** True for a row of tablature: mostly dashes, fret numbers and bar lines. */
    fun isTab(line: String): Boolean {
        if (line.count { it == '-' } < 6) return false
        val body = TAB_HEAD.replaceFirst(line, "")
        return TAB_REST.replace(body, "").isEmpty()
    }

    /**
     * A line that is only chords (and the odd bar line or "x2"): its pieces in order, and the
     * chords with the columns they stand at. Null for a line of words.
     */
    fun chordLine(line: String): Pair<List<Mark>, List<Placed>>? {
        val marks = ArrayList<Mark>()
        val placed = ArrayList<Placed>()
        var any = false
        var at = 0
        for (token in line.split(SPACES)) {
            if (token.isEmpty()) continue
            val column = line.indexOf(token, at)
            at = column + token.length
            val bare = token.trim('(', ')', ',', '|')
            val chord = Chord.parse(bare)
            when {
                chord != null -> {
                    any = true
                    marks += Mark.Of(chord)
                    placed += Placed(column + token.indexOf(bare).coerceAtLeast(0), chord)
                }
                FILLER.matches(token) -> marks += Mark.Word(token)
                else -> return null
            }
        }
        return if (any) marks to placed else null
    }

    private val EDGE_BEFORE = Regex("""^[(\[\{"'“‘]+""")
    private val EDGE_AFTER = Regex("""[)\]\}"'”’.,;:!?]+$""")
    private val CUE = Regex("""(?i)\b(repeat|play|intro|outro|riff|chords?|strum|capo|ending|end on|bridge|solo|interlude|break|hold|x\s?\d)\b|:""")
    private val JOINS = setOf("and", "to", "then", "or", "-", "–", "/", "|", ",", "x2", "x3", "x4")

    /**
     * A line of words read as a sentence with chords in it, or null when it is only words. It
     * takes more than a capital letter to be a chord here: the line must name a chord that could
     * be nothing else ("Em7", "F#", "Csus4"), or two plain letters with a word that says it is
     * about playing ("Repeat C and G"), or three. And "A" and "Am" are words unless they stand
     * beside another chord or a joining word.
     */
    fun said(line: String): SheetLine.Said? {
        class Token(val before: String, val core: String, val after: String, val chord: Chord?)
        val tokens = line.trim().split(SPACES).filter { it.isNotEmpty() }.map { t ->
            val before = EDGE_BEFORE.find(t)?.value.orEmpty()
            val after = EDGE_AFTER.find(t.substring(before.length))?.value.orEmpty()
            val core = t.substring(before.length, t.length - after.length)
            Token(before, core, after, Chord.parse(core))
        }
        fun plain(c: Chord) = c.quality.isEmpty() && c.bass == null && c.root.length == 1
        fun wordish(t: Token) = t.chord != null && (t.core == "A" || t.core == "Am" || t.core == "I")
        val isChord = BooleanArray(tokens.size) { tokens[it].chord != null && !wordish(tokens[it]) }
        // "A" and "Am" beside a chord or a joining word.
        for (i in tokens.indices) {
            if (!wordish(tokens[i]) || tokens[i].core == "I") continue
            val near = listOf(i - 1, i + 1).filter { it in tokens.indices }
            if (near.any { isChord[it] || tokens[it].core.lowercase() in JOINS }) isChord[i] = true
        }
        val chords = tokens.indices.filter { isChord[it] }.map { tokens[it].chord!! }
        val sure = chords.any { !plain(it) }
        val enough = sure || chords.size >= 3 || (chords.size >= 2 && CUE.containsMatchIn(line))
        if (!enough) return null
        return SheetLine.Said(tokens.mapIndexed { i, t ->
            if (isChord[i]) Mark.Of(t.chord!!, t.before, t.after) else Mark.Word(t.before + t.core + t.after)
        })
    }

    /** Every different chord in the song, in the order each first comes. */
    fun chords(lines: List<SheetLine>): List<Chord> {
        val seen = LinkedHashMap<String, Chord>()
        for (l in lines) when (l) {
            is SheetLine.Chords -> l.marks.forEach { if (it is Mark.Of) seen.putIfAbsent(it.chord.name, it.chord) }
            is SheetLine.Sung -> l.chords.forEach { seen.putIfAbsent(it.chord.name, it.chord) }
            is SheetLine.Said -> l.marks.forEach { if (it is Mark.Of) seen.putIfAbsent(it.chord.name, it.chord) }
            else -> Unit
        }
        return seen.values.toList()
    }

    /**
     * Whether a song moved by [semitones] reads better in flats: it does when the chords as
     * written use flats and no sharps, or when the move lands the first chord on a flat key.
     */
    fun flats(lines: List<SheetLine>, semitones: Int): Boolean {
        val all = chords(lines)
        val flat = all.count { it.root.endsWith("b") }
        val sharp = all.count { it.root.endsWith("#") }
        if (flat != sharp) return flat > sharp
        val first = all.firstOrNull() ?: return false
        val minor = tonesOf(first.quality).needed.contains(3)
        val home = Math.floorMod(first.rootPc + semitones + if (minor) 3 else 0, 12)
        // F, Bb, Eb, Ab and Db are written in flats.
        return home in setOf(5, 10, 3, 8, 1)
    }

    /** A word of a sung line, cut where chords fall inside it; the pieces stay side by side. */
    data class Piece(val chord: Chord?, val text: String)

    /**
     * A sung line as words, each a run of [Piece]s, so the words can wrap like any sentence
     * while every chord stays over its syllable. A chord standing over a gap goes to the word
     * after it; one past the end of the words stands alone.
     */
    fun words(line: SheetLine.Sung): List<List<Piece>> {
        val text = line.words
        val out = ArrayList<List<Piece>>()
        val chords = line.chords.sortedBy { it.column }
        var c = 0
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            val pieces = ArrayList<Piece>()
            var from = i
            var over: Chord? = null
            // Chords standing before this word, over the gap: the last of them sits on the word,
            // any before it stand alone.
            while (c < chords.size && chords[c].column <= i) {
                over?.let { out += listOf(Piece(it, "")) }
                over = chords[c++].chord
            }
            while (c < chords.size && chords[c].column < end) {
                val cut = chords[c].column
                pieces += Piece(over, text.substring(from, cut))
                over = chords[c++].chord
                from = cut
            }
            pieces += Piece(over, text.substring(from, end))
            out += pieces
            i = end
        }
        while (c < chords.size) out += listOf(Piece(chords[c++].chord, ""))
        return out
    }

    /**
     * The song as plain text again, every chord moved by [semitones]: chords back over their
     * words at their columns, nudged right where a longer name would run into the next.
     */
    fun plain(lines: List<SheetLine>, semitones: Int): String {
        val flats = flats(lines, semitones)
        fun name(c: Chord) = c.transposed(semitones, flats).name
        val sb = StringBuilder()
        for (l in lines) {
            when (l) {
                SheetLine.Blank -> Unit
                is SheetLine.Part -> sb.append('[').append(l.name).append(']')
                is SheetLine.Chords -> sb.append(l.marks.joinToString("  ") { if (it is Mark.Of) name(it.chord) else (it as Mark.Word).text })
                is SheetLine.Sung -> {
                    val row = StringBuilder()
                    for (p in l.chords.sortedBy { it.column }) {
                        val gap = if (row.isEmpty()) p.column else maxOf(p.column, row.length + 1)
                        while (row.length < gap) row.append(' ')
                        row.append(name(p.chord))
                    }
                    sb.append(row).append('\n').append(l.words)
                }
                is SheetLine.Tab -> sb.append(l.rows.joinToString("\n"))
                is SheetLine.Said -> sb.append(sentence(l) { name(it) })
                is SheetLine.Words -> sb.append(l.text)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** A sentence with chords in it written out again, each chord as [name] gives it. */
    fun sentence(line: SheetLine.Said, name: (Chord) -> String): String =
        line.marks.joinToString(" ") { if (it is Mark.Of) it.before + name(it.chord) + it.after else (it as Mark.Word).text }

    /**
     * A system of tablature cut into pieces no wider than [width] characters, every string cut
     * at the same place, so a long line reads on down the page instead of off its edge.
     */
    fun cut(rows: List<String>, width: Int): List<List<String>> {
        val longest = rows.maxOfOrNull { it.length } ?: 0
        if (width <= 0 || longest <= width) return listOf(rows)
        // Pieces of about the same width, rather than full ones and a stub.
        val pieces = (longest + width - 1) / width
        val each = (longest + pieces - 1) / pieces
        val out = ArrayList<List<String>>()
        var from = 0
        while (from < longest) {
            val to = minOf(longest, from + each)
            out += rows.map { if (from >= it.length) "" else it.substring(from, minOf(it.length, to)) }
            from = to
        }
        return out
    }
}
