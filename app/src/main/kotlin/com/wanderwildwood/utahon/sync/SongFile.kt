package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.music.ChordPro
import com.wanderwildwood.utahon.music.Sheet

/**
 * A song as a file in the Nextcloud folder: ChordPro, the format songbook programs share, so
 * the songs there can be read and changed by other programs and on other machines, and one put
 * there by hand comes here. What ChordPro has no word for (where the sheet came from, its
 * tuning, how the reader has set it) rides along in lines of the app's own, which ChordPro lets
 * a program add under an `x_` name and other programs pass over.
 */
object SongFile {

    const val EXTENSION = ".cho"

    /** The endings a ChordPro file goes by. */
    private val ENDINGS = listOf(".cho", ".chopro", ".chordpro", ".crd", ".pro")

    fun isSong(fileName: String): Boolean = ENDINGS.any { fileName.endsWith(it, ignoreCase = true) }

    private val OWN = Regex("""^\{x_songbook_([a-z]+):\s*(.*?)\s*\}\s*$""")

    fun toFile(s: Song): String {
        val body = ChordPro.write(s.title, s.artist, s.capo, s.key, Sheet.read(s.text), 0)
        // ChordPro cannot say everything a sheet holds; where reading the body back would not
        // give the sheet word for word, the sheet itself rides along too, so a sync never
        // changes it.
        // In quotes, so spaces at either end of the sheet are kept too.
        val whole = if (ChordPro.read(body).text != s.text) "\"" + escape(s.text) + "\"" else ""
        val own = buildString {
            fun line(name: String, value: String) { if (value.isNotEmpty()) append("{x_songbook_").append(name).append(": ").append(value).append("}\n") }
            line("id", s.id)
            line("kind", s.kind)
            line("tuning", s.tuning)
            line("url", s.url)
            line("transpose", if (s.transpose != 0) s.transpose.toString() else "")
            line("speed", if (s.speed != 0) s.speed.toString() else "")
            line("length", if (s.length != 0) s.length.toString() else "")
            line("created", s.created)
            line("modified", s.modified)
            line("sheet", whole)
        }
        return own + body
    }

    /** A sheet on one line: each backslash doubled, each line end written as a backslash and n. */
    internal fun escape(text: String): String = buildString {
        for (c in text.replace("\r\n", "\n")) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    internal fun unescape(line: String): String = buildString {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                append(if (line[i + 1] == 'n') '\n' else line[i + 1])
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /**
     * The song a file holds, or null when it holds neither a title nor a sheet. With [verbatim],
     * a sheet this app wrote beside the body is taken word for word, as long as the body is
     * still what that sheet makes (nobody changed the file elsewhere); otherwise the body is read.
     */
    fun fromFile(text: String, fileName: String, folder: String = "", verbatim: Boolean = true): Song? {
        val own = HashMap<String, String>()
        val rest = text.replace("\r\n", "\n").split('\n').filter { line ->
            val m = OWN.matchEntire(line) ?: return@filter true
            own[m.groupValues[1]] = m.groupValues[2]
            false
        }.joinToString("\n")
        val pro = ChordPro.read(rest)
        val title = pro.title.ifBlank { fileName.substringBeforeLast('.').trim() }
        if (title.isBlank() && pro.text.isBlank()) return null
        val sheet = own["sheet"]?.takeIf { verbatim && it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") }
            ?.let { unescape(it.substring(1, it.length - 1)) }?.takeIf { whole ->
            ChordPro.write(pro.title, pro.artist, pro.capo, pro.key, Sheet.read(whole), 0).trimEnd() == rest.trimEnd()
        }
        return Song(
            id = own["id"].orEmpty(),
            title = title,
            // A file with no artist written in it is by whoever's folder it was put in.
            artist = pro.artist.ifBlank { folder.trim() },
            text = sheet ?: pro.text,
            kind = own["kind"].orEmpty(),
            capo = pro.capo,
            tuning = own["tuning"].orEmpty(),
            key = pro.key,
            url = own["url"].orEmpty(),
            transpose = own["transpose"]?.toIntOrNull()?.coerceIn(-11, 11) ?: 0,
            speed = own["speed"]?.toIntOrNull()?.coerceIn(0, 9) ?: 0,
            length = own["length"]?.toIntOrNull()?.coerceIn(0, 3600) ?: 0,
            created = own["created"].orEmpty(),
            modified = own["modified"].orEmpty(),
        )
    }

    /**
     * Whether two songs are the same song as a file would carry them: who and what, the sheet,
     * and how it is set; not their ids or stamps. A sheet is compared as it comes back from a
     * file, since writing one out evens its spacing.
     */
    fun same(a: Song, b: Song): Boolean {
        fun canon(s: Song) = fromFile(toFile(s.copy(id = "", created = "", modified = "")), "", verbatim = false)
        return canon(a) == canon(b)
    }

    private val FORBIDDEN = Regex("""[/:?*"\\<>|\u0000-\u001f]""")
    private const val MAX = 100

    /** The file a song goes in, without its ending: its title, "Lantern Road". */
    fun fileName(s: Song): String = clean(s.title).ifEmpty { "Song" }

    /** The folder a song goes in: its artist's, "Ada Whitlock", or none when it names no artist. */
    fun folderName(s: Song): String = clean(s.artist)

    /** Where a song goes under the song folder: "Ada Whitlock/Lantern Road". */
    fun place(s: Song): String = placeOf(folderName(s), fileName(s))

    /** How songs were named before each artist had a folder: "Lantern Road - Ada Whitlock", all in one folder. */
    fun oldName(s: Song): String = clean(if (s.artist.isBlank()) s.title else "${s.title} - ${s.artist}").ifEmpty { "Song" }

    /** A title or an artist as a file or folder name Nextcloud will take and show. */
    private fun clean(text: String): String {
        var n = text.trim().replace(FORBIDDEN, "_")
        if (n.codePointCount(0, n.length) > MAX) n = n.substring(0, n.offsetByCodePoints(0, MAX))
        n = n.trim().trimEnd('.')
        if (n.startsWith(".")) n = "_" + n.substring(1)
        return n
    }

    /** [wanted], or "wanted (2)" and on, whichever is not in [taken] (compared ignoring case). */
    fun unique(wanted: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (wanted.lowercase() !in lower) return wanted
        var i = 2
        while ("$wanted ($i)".lowercase() in lower) i++
        return "$wanted ($i)"
    }
}
