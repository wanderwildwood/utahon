package com.wanderwildwood.utahon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's pattern engine is stricter than the one these tests run on: a brace that is not
 * part of a count ("{3,}") or a class name ("\p{L}") has to be escaped there, and a pattern that
 * leaves one bare compiles here and crashes the app on the phone. (It did, on the first sync:
 * a closing brace in the song file's pattern.) So every pattern written in the app is read from
 * its source and checked for a bare brace.
 */
class PatternsTest {

    private val literal = Regex("Regex\\(\\s*(\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")", RegexOption.DOT_MATCHES_ALL)

    /** The braces left in [pattern] once escapes, class names and counts are taken out. */
    private fun bare(pattern: String): String = pattern
        .replace(Regex("""\\[pP]\{[A-Za-z_]+\}"""), "")
        .replace(Regex("""\\."""), "")
        .replace(Regex("""\{\d+(,\d*)?\}"""), "")
        .filter { it == '{' || it == '}' }

    @Test fun noPatternHasABareBrace() {
        val bad = mutableListOf<String>()
        var seen = 0
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            for (m in literal.findAll(file.readText())) {
                val raw = m.groupValues[2].ifEmpty { m.groupValues[3].replace("\\\\", "\\") }
                if (raw.contains('$') && raw.contains("{") && raw.contains("\${")) continue
                seen++
                if (bare(raw).isNotEmpty()) bad += "${file.name}: $raw"
            }
        }
        assertTrue("found only $seen patterns: is the source where this expects it?", seen > 20)
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun theCheckCatchesTheOneThatCrashed() {
        assertEquals("}", bare("""^\{x_songbook_([a-z]+):\s*(.*?)\s*}\s*$"""))
        assertEquals("", bare("""^\{x_songbook_([a-z]+):\s*(.*?)\s*\}\s*$"""))
        assertEquals("", bare("""[^\p{L}\p{Nd}]+\n{3,}x{2}"""))
    }
}
