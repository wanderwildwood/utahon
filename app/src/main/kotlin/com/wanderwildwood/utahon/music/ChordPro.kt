package com.wanderwildwood.utahon.music

/**
 * ChordPro, the format most songbook programs read and write: the chords sit inside the line
 * in square brackets, "[C]Lantern on the [Am]gatepost", and lines in braces say what the song
 * is called and where its parts begin. This app keeps a sheet the way people write one by hand,
 * chords on a line of their own over the words, so a ChordPro file is turned into that on the
 * way in and back on the way out.
 */
object ChordPro {

    /** What a ChordPro file says about its song, and its sheet as chords over words. */
    data class Read(val title: String, val artist: String, val capo: Int, val key: String, val text: String)

    private val DIRECTIVE = Regex("""^\s*\{\s*([A-Za-z_]+)\s*(?::\s*(.*?))?\s*\}\s*$""")
    private val INLINE = Regex("""\[([^\[\]\s]{1,12})]""")

    /**
     * True for text written as ChordPro: it has a line in braces that names the song or a part,
     * or a line of words with a chord in brackets inside it.
     */
    fun looksLike(text: String): Boolean = text.lineSequence().any { line ->
        val d = DIRECTIVE.matchEntire(line)?.groupValues?.get(1)?.lowercase()
        if (d != null) d in KNOWN else inlineChords(line)
    }

    private val KNOWN = setOf(
        "title", "t", "subtitle", "st", "artist", "capo", "key", "comment", "c", "ci", "comment_italic",
        "soc", "eoc", "start_of_chorus", "end_of_chorus", "sov", "eov", "start_of_verse", "end_of_verse",
        "sob", "eob", "start_of_bridge", "end_of_bridge", "sot", "eot", "start_of_tab", "end_of_tab", "chorus",
    )

    /** True for a line of words with at least one chord in brackets, and something besides. */
    private fun inlineChords(line: String): Boolean {
        val chords = INLINE.findAll(line).filter { Chord.parse(it.groupValues[1]) != null }.toList()
        if (chords.isEmpty()) return false
        return INLINE.replace(line, "").isNotBlank() || chords.size >= 2
    }

    fun read(text: String): Read {
        var title = ""
        var artist = ""
        var capo = 0
        var key = ""
        var tab = false
        val out = ArrayList<String>()
        fun part(name: String, label: String) {
            if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
            out += "[" + label.ifBlank { name } + "]"
        }
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("#")) continue
            val d = DIRECTIVE.matchEntire(line)
            if (d != null) {
                val value = d.groupValues[2].trim()
                when (d.groupValues[1].lowercase()) {
                    "title", "t" -> title = value
                    "subtitle", "st", "artist" -> if (artist.isEmpty()) artist = value
                    "capo" -> capo = value.toIntOrNull()?.coerceIn(0, 24) ?: 0
                    "key" -> key = value
                    "comment", "c", "ci", "comment_italic" -> if (value.isNotEmpty()) out += if (value.length <= 40) "[$value]" else value
                    "soc", "start_of_chorus" -> part("Chorus", value)
                    "sov", "start_of_verse" -> part("Verse", value)
                    "sob", "start_of_bridge" -> part("Bridge", value)
                    "chorus" -> out += "[Chorus]"
                    "sot", "start_of_tab" -> tab = true
                    "eot", "end_of_tab" -> tab = false
                    "eoc", "end_of_chorus", "eov", "end_of_verse", "eob", "end_of_bridge" -> if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
                    else -> Unit
                }
                continue
            }
            if (tab || !INLINE.containsMatchIn(line)) {
                out += line
                continue
            }
            out += over(line)
        }
        val sheet = out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n') + "\n"
        return Read(title, artist, capo, key, sheet)
    }

    /**
     * One ChordPro line as chords over words: the brackets taken out of the words, each chord
     * set on the line above at the place it stood. Where two chords stand closer than the first
     * one's name is long, the words are spread a little so each chord keeps its syllable.
     */
    internal fun over(line: String): List<String> {
        val words = StringBuilder()
        val chords = StringBuilder()
        var at = 0
        var any = false
        for (m in INLINE.findAll(line)) {
            val name = m.groupValues[1]
            if (Chord.parse(name) == null) continue
            words.append(line, at, m.range.first)
            at = m.range.last + 1
            // Room for the chord before this one.
            while (any && words.length < chords.length + 1) words.append(' ')
            while (chords.length < words.length) chords.append(' ')
            chords.append(name)
            any = true
        }
        words.append(line, at, line.length)
        if (!any) return listOf(line)
        val w = words.toString().trimEnd()
        return if (w.isBlank()) listOf(chords.toString().trim().replace(Regex(" +"), "  ")) else listOf(chords.toString().trimEnd(), w)
    }

    /** A song as a ChordPro file, its chords and tablature moved by [semitones]. */
    fun write(title: String, artist: String, capo: Int, key: String, lines: List<SheetLine>, semitones: Int): String {
        val flats = Sheet.flats(lines, semitones)
        fun name(c: Chord) = "[" + c.transposed(semitones, flats).name + "]"
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append("{title: ").append(title).append("}\n")
        if (artist.isNotBlank()) sb.append("{artist: ").append(artist).append("}\n")
        if (capo > 0) sb.append("{capo: ").append(capo).append("}\n")
        if (key.isNotBlank() && semitones == 0) sb.append("{key: ").append(key).append("}\n")
        if (sb.isNotEmpty()) sb.append('\n')
        for (l in Tablature.movedLines(lines, semitones)) {
            when (l) {
                SheetLine.Blank -> Unit
                is SheetLine.Part -> sb.append("{comment: ").append(l.name).append('}')
                is SheetLine.Chords -> sb.append(l.marks.joinToString(" ") { if (it is Mark.Of) name(it.chord) else (it as Mark.Word).text })
                is SheetLine.Sung -> {
                    var at = 0
                    for (p in l.chords.sortedBy { it.column }) {
                        val col = p.column.coerceIn(at, maxOf(at, l.words.length))
                        sb.append(l.words, at, col)
                        // A chord past the end of the words keeps a space before it.
                        if (p.column > l.words.length) sb.append(' ')
                        sb.append(name(p.chord))
                        at = col
                    }
                    sb.append(l.words, at, l.words.length)
                }
                is SheetLine.Tab -> sb.append("{start_of_tab}\n").append(l.rows.joinToString("\n")).append("\n{end_of_tab}")
                // A chord named in a sentence stays a word in it; it is written in the key the rest is.
                is SheetLine.Said -> sb.append(Sheet.sentence(l) { it.transposed(semitones, flats).name })
                is SheetLine.Words -> sb.append(l.text)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }
}
