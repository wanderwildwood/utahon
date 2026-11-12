package com.wanderwildwood.utahon.importer

import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.music.Sheet
import com.wanderwildwood.utahon.music.SheetLine
import org.json.JSONObject

/**
 * Finding the song on a page. Some chord sites hand their pages their contents as one block of
 * data, with the sheet in it as text and each chord marked; that is read first. Any other page
 * is searched for a block of preformatted text with chords in it, which is how most chord sites
 * set a sheet.
 */
object SongPage {

    private val STORE = Regex("""(?is)<div\b[^>]*\bclass\s*=\s*"[^"]*\bjs-store\b[^"]*"[^>]*\bdata-content\s*=\s*"([^"]*)"""")
    private val CHORD_TAG = Regex("""\[/?ch]""")
    private val TAB_TAG = Regex("""\[/?tab]""")
    private val PRE = Regex("""(?is)<pre\b[^>]*>(.*?)</pre\s*>""")
    private val TAG = Regex("""<[^>]*>""")
    private val BREAK = Regex("""(?i)<br\s*/?>""")

    /**
     * The song a page carries in a block of data, or null when the page has none or has no sheet
     * as text. The data comes in two shapes, depending on who asks: the song under
     * the page's own data, with the sheet in a "wiki tab" beside it, or the song with its sheet
     * at the top of the store. Both are read.
     */
    fun stored(html: String, url: String): Song? {
        val data = STORE.find(html)?.groupValues?.get(1) ?: return null
        return runCatching {
            val store = JSONObject(Html.decode(data, passes = 1)).getJSONObject("store")
            val page = store.optJSONObject("page")?.optJSONObject("data")
            val view = page?.optJSONObject("tab_view")
            val tab = page?.optJSONObject("tab") ?: store.optJSONObject("tab") ?: return null
            val content = view?.optJSONObject("wiki_tab")?.optString("content").orEmpty().ifBlank { tab.optString("content") }
            if (content.isBlank()) return null
            val meta = view?.optJSONObject("meta")
            Song(
                id = "",
                title = tab.optString("song_name").trim(),
                artist = tab.optString("artist_name").trim(),
                text = sheetText(content),
                kind = tab.optString("type").ifBlank { tab.optString("type_name") }.trim(),
                capo = (meta?.optInt("capo", 0) ?: tab.optInt("capo", 0)).coerceIn(0, 24),
                tuning = (meta?.optJSONObject("tuning")?.optString("value") ?: tab.optString("tuning")).trim(),
                key = meta?.optString("tonality").orEmpty().ifBlank { tab.optString("tonality_name") }.trim(),
                url = tab.optString("tab_url").ifBlank { url },
            )
        }.getOrNull()?.takeIf { it.title.isNotEmpty() || it.text.isNotEmpty() }
    }

    /** A sheet as such a block of data keeps it, with its chord and tab marks taken out: plain chords over words. */
    fun sheetText(content: String): String =
        content.replace("\r\n", "\n").replace('\r', '\n')
            .replace(CHORD_TAG, "").replace(TAB_TAG, "")
            .split('\n').joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n").trim('\n') + "\n"

    /**
     * The sheet on any other page: its largest block of preformatted text that has chords or
     * tablature in it. The page's title is the song's, for the reader to put right.
     */
    fun preformatted(html: String, url: String): Song? {
        val best = PRE.findAll(html)
            .map { Html.decode(it.groupValues[1].replace(BREAK, "\n").replace(TAG, "")).replace("\r\n", "\n").replace(' ', ' ') }
            .filter { looksLikeSheet(it) }
            .maxByOrNull { it.length } ?: return null
        val text = best.split('\n').joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim('\n') + "\n"
        return Song(id = "", title = Html.title(html), text = text, url = url)
    }

    /** True for text with at least two lines of chords or tablature in it. */
    fun looksLikeSheet(text: String): Boolean =
        Sheet.read(text).count { it is SheetLine.Sung || it is SheetLine.Chords || it is SheetLine.Tab } >= 2
}
