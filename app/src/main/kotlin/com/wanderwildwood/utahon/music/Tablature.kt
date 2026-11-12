package com.wanderwildwood.utahon.music

/**
 * Tablature moved with the chords. A fret number is a count of semitones up its string, so a
 * song moved up two has every fret two higher; bars, dashes, the letters between notes (h, p,
 * b, /, \, ~, x) and the names of the strings stay as they are written.
 */
object Tablature {

    /** A fret number on a string: the columns it stands in ([from] until [to]), and the fret. */
    data class Fret(val from: Int, val to: Int, val number: Int)

    /** Two digits side by side are one fret up to here ("12"), two frets past it ("35"). */
    private const val HIGHEST = 24

    /** Where the notes of a row begin: after the name of its string. */
    private fun start(row: String): Int {
        val bar = row.indexOf('|')
        // "e|", "1|", "D#|": whatever stands before the first bar with no dash in it is a name.
        if (bar >= 0 && row.lastIndexOf('-', bar) < 0) return bar
        return Sheet.TAB_HEAD.find(row)?.let { it.range.last + 1 } ?: 0
    }

    /** The fret numbers written on one row of tablature. */
    fun frets(row: String): List<Fret> {
        val from = start(row)
        // "x2" after the closing bar counts times round, not a fret.
        val close = row.lastIndexOf('|')
        val to = if (close > from && row.indexOf('-', close) < 0) close else row.length
        val out = ArrayList<Fret>()
        var i = from
        while (i < to) {
            if (row[i] !in '0'..'9') { i++; continue }
            val two = if (i + 1 < to && row[i + 1] in '0'..'9') (row[i] - '0') * 10 + (row[i + 1] - '0') else -1
            val wide = two in 10..HIGHEST
            out += Fret(i, i + if (wide) 2 else 1, if (wide) two else row[i] - '0')
            i += if (wide) 2 else 1
        }
        return out
    }

    /**
     * How far tablature whose lowest fret is [lowest] moves for a song moved by [semitones]. A
     * fret cannot go below the open string, so a move down that would ask for one is played the
     * same notes an octave up instead: down two becomes up ten.
     */
    private fun playable(lowest: Int, semitones: Int): Int =
        if (lowest + semitones < 0) Math.floorMod(semitones, 12) else semitones

    /**
     * How far all the tablature of a song moves when the song is moved by [semitones]: the same
     * for every system, so a part that goes up an octave takes the rest with it.
     */
    fun shift(lines: List<SheetLine>, semitones: Int): Int {
        val lowest = lines.filterIsInstance<SheetLine.Tab>().flatMap { it.rows }.flatMap { frets(it) }
            .minOfOrNull { it.number } ?: return semitones
        return playable(lowest, semitones)
    }

    /** The lines of a song with its tablature moved by [semitones]; the other lines as they were. */
    fun movedLines(lines: List<SheetLine>, semitones: Int): List<SheetLine> {
        val by = shift(lines, semitones)
        if (by == 0 || lines.none { it is SheetLine.Tab }) return lines
        return lines.map { if (it is SheetLine.Tab) SheetLine.Tab(moved(it.rows, by)) else it }
    }

    private class Piece(val text: String, val end: Int, val need: Int)

    private const val OPEN = "-| "

    /**
     * One system moved by [semitones], its strings still in step. A number that grows a digit
     * (9 to 10) takes the dash after it when there is one to spare; when there is not, every
     * string is given a column at that place. A number that loses a digit leaves a dash.
     */
    fun moved(rows: List<String>, semitones: Int): List<String> {
        val frets = rows.map { frets(it) }
        val lowest = frets.flatten().minOfOrNull { it.number } ?: return rows
        val by = playable(lowest, semitones)
        if (by == 0) return rows
        val starts = rows.map { start(it) }
        // Columns to be added before each column of the system as it was written.
        val wider = IntArray(rows.maxOf { it.length } + 2)
        fun spare(r: Int, c: Int): Boolean = rows[r].getOrNull(c).let { it == null || it in OPEN }
        val pieces = rows.indices.map { r ->
            val row = rows[r]
            frets[r].map { f ->
                var text = (f.number + by).toString()
                var end = f.to
                val was = f.to - f.from
                if (text.length < was) {
                    val pad = "-".repeat(was - text.length)
                    // "10h12" down three is "-7h9-": the dash goes where it parts nothing.
                    val joined = f.to < row.length && row[f.to] !in OPEN
                    text = if (joined && row.getOrNull(f.from - 1) == '-') pad + text else text + pad
                } else {
                    // A dash to spare: two after the number on its own string, and no note of
                    // another string in that column, which the longer number would seem to join.
                    while (end - f.from < text.length && row.getOrNull(end) == '-' && row.getOrNull(end + 1) == '-' &&
                        rows.indices.all { it == r || spare(it, end) }
                    ) end++
                }
                val need = (text.length - (end - f.from)).coerceAtLeast(0)
                if (need > wider[end]) wider[end] = need
                Piece(text, end, need)
            }
        }
        return rows.indices.map { r ->
            val row = rows[r]
            val out = StringBuilder(row.length + 4)
            var c = 0
            var k = 0
            var owed = 0
            var own = 0
            while (true) {
                owed += wider[c] - own
                own = 0
                if (c >= row.length) break
                // The added columns go in among the dashes, never into "7h9" or the string's name.
                if (owed > 0 && c > starts[r] && (row[c] in OPEN || row[c - 1] in OPEN)) {
                    val fill = if (row[c] == ' ' || (row[c - 1] == ' ' && row[c] != '-')) ' ' else '-'
                    repeat(owed) { out.append(fill) }
                    owed = 0
                }
                val f = frets[r].getOrNull(k)
                if (f != null && f.from == c) {
                    val p = pieces[r][k++]
                    out.append(p.text)
                    for (b in c + 1 until p.end) owed += wider[b]
                    own = p.need
                    c = p.end
                } else {
                    out.append(row[c++])
                }
            }
            out.toString()
        }
    }
}
