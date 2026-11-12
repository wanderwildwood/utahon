package com.wanderwildwood.utahon.importer

/**
 * The little of HTML a song page needs read: its entities and its title. Written out here
 * rather than taken from Android's Html class so the reading can be tested off the phone.
 */
object Html {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "frac13" to "⅓", "frac23" to "⅔",
        "frac18" to "⅛", "frac38" to "⅜", "frac58" to "⅝", "frac78" to "⅞", "half" to "½",
        "deg" to "°", "ndash" to "–", "mdash" to "—", "hellip" to "…", "times" to "×",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à",
        "acirc" to "â", "iacute" to "í", "oacute" to "ó", "ocirc" to "ô", "uacute" to "ú",
        "ntilde" to "ñ", "ccedil" to "ç", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
        "copy" to "©", "reg" to "®", "trade" to "™", "frasl" to "⁄", "minus" to "−",
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã", "Auml" to "Ä", "Aring" to "Å",
        "AElig" to "Æ", "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
        "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï", "Ntilde" to "Ñ", "Ograve" to "Ò",
        "Oacute" to "Ó", "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø", "Ugrave" to "Ù",
        "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "atilde" to "ã", "aring" to "å", "aelig" to "æ",
        "euml" to "ë", "igrave" to "ì", "icirc" to "î", "iuml" to "ï", "ograve" to "ò", "otilde" to "õ",
        "oslash" to "ø", "ugrave" to "ù", "ucirc" to "û", "yacute" to "ý", "iexcl" to "¡", "iquest" to "¿",
    )

    private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#\d{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

    /**
     * Entities turned back into characters. Twice where the page encoded them twice, which
     * some pages do: "&amp;#39;" is an apostrophe that went through two kettles. An attribute
     * holding data is decoded once ([passes] = 1), or a quote mark its text spells out would end
     * a string early.
     */
    fun decode(text: String, passes: Int = 2): String {
        var s = text
        repeat(passes) {
            if (!s.contains('&')) return s
            s = ENTITY.replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                    e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                    else -> NAMED[e] ?: NAMED[e.lowercase()] ?: m.value
                }
            }
        }
        return s
    }

    private fun codePoint(c: Int): String? =
        if (c in 1..0x10FFFF && c !in 0xD800..0xDFFF) String(Character.toChars(c)) else null

    private val BREAKS = Regex("""(?i)<\s*(br|/p|/li|/div|/h[1-6]|li|p|tr)\b[^>]*>""")
    private val TAG = Regex("""<[^>]*>""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val INVISIBLE = Regex("""(?is)<(script|style|noscript)\b.*?</\1\s*>""")

    /**
     * The words of a piece of HTML, as lines: tags gone, entities decoded, a break, paragraph
     * or list item a new line, spaces run together. Blank lines are dropped.
     */
    fun lines(html: String): List<String> {
        val s = html.replace(COMMENT, " ").replace(INVISIBLE, " ").replace(BREAKS, "\n").replace(TAG, " ")
        return decode(s).split('\n').map(::tidy).filter { it.isNotEmpty() }
    }

    /** The words of a piece of HTML on one line. */
    fun text(html: String): String = lines(html).joinToString(" ")

    /** Spaces of every kind run together into one, and the ends trimmed. */
    fun tidy(text: String): String =
        text.replace(' ', ' ').replace(Regex("""[\s​﻿]+"""), " ").trim()

    private val OG_TITLE = Regex("""(?is)<meta\b[^>]*\bproperty\s*=\s*["']og:title["'][^>]*>""")
    private val CONTENT = Regex("""(?is)\bcontent\s*=\s*("([^"]*)"|'([^']*)')""")
    private val TITLE = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")

    /** What the page calls itself: its og:title, or failing that its `<title>`. */
    fun title(html: String): String {
        OG_TITLE.find(html)?.let { tag ->
            CONTENT.find(tag.value)?.let { c ->
                val t = tidy(decode(c.groupValues[2].ifEmpty { c.groupValues[3] }))
                if (t.isNotEmpty()) return t
            }
        }
        return TITLE.find(html)?.let { tidy(decode(it.groupValues[1].replace(TAG, " "))) }.orEmpty()
    }
}
