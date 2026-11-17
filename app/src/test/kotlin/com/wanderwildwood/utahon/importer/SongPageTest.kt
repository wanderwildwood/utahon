package com.wanderwildwood.utahon.importer

import com.wanderwildwood.utahon.music.Sheet
import com.wanderwildwood.utahon.music.SheetLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages made up for the tests, in the shapes the real ones have. No real song is in here. */
class SongPageTest {

    private val content = "[Intro]\r\n[ch]C[/ch]   [ch]Am[/ch]\r\n\r\n\r\n\r\n[Verse]\r\n" +
        "[tab][ch]C[/ch]              [ch]Am[/ch]\r\nLantern on the gatepost[/tab]\r\n" +
        "[tab]       [ch]F[/ch]           [ch]G[/ch]\r\nShows the road is \"long\" & slow[/tab]\r\n"

    private fun page(content: String?, type: String = "Chords", capo: Int = 2): String {
        val wiki = JSONObject()
        if (content != null) wiki.put("content", content)
        val data = JSONObject().put(
            "store",
            JSONObject().put(
                "page",
                JSONObject().put(
                    "data",
                    JSONObject()
                        .put("tab", JSONObject().put("song_name", "Lantern Road").put("artist_name", "Ada Whitlock").put("type", type)
                            .put("tab_url", "https://tabs.example.com/tab/ada-whitlock/lantern-road-chords-1"))
                        .put("tab_view", JSONObject().put("wiki_tab", wiki)
                            .put("meta", JSONObject().put("capo", capo).put("tonality", "C").put("tuning", JSONObject().put("value", "E A D G B E")))),
                ),
            ),
        )
        val attr = data.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><head><title>LANTERN ROAD CHORDS by Ada Whitlock</title></head><body><div class=\"js-store\" data-content=\"$attr\"></div></body></html>"
    }

    @Test fun aPageCannotPointItsSongAtAnotherSite() {
        assertEquals("https://tabs.example.com/tab/1", SongPage.sameSite("https://tabs.example.com/tab/1", "https://tabs.example.com/x"))
        assertEquals("https://tabs.example.com/x", SongPage.sameSite("https://elsewhere.example.org/tab/1", "https://tabs.example.com/x"))
        assertEquals("https://tabs.example.com/x", SongPage.sameSite("market://details?id=x", "https://tabs.example.com/x"))
        assertEquals("https://tabs.example.com/x", SongPage.sameSite("", "https://tabs.example.com/x"))
    }

    @Test fun readsAPageThatCarriesItsSheetAsData() {
        val song = SongPage.stored(page(content), "https://tabs.example.com/x")!!
        assertEquals("Lantern Road", song.title)
        assertEquals("Ada Whitlock", song.artist)
        assertEquals("Chords", song.kind)
        assertEquals(2, song.capo)
        assertEquals("C", song.key)
        assertEquals("E A D G B E", song.tuning)
        assertEquals("https://tabs.example.com/tab/ada-whitlock/lantern-road-chords-1", song.url)
        assertEquals(
            "[Intro]\nC   Am\n\n[Verse]\nC              Am\nLantern on the gatepost\n       F           G\nShows the road is \"long\" & slow\n",
            song.text,
        )
        val lines = Sheet.read(song.text)
        assertEquals(2, lines.count { it is SheetLine.Sung })
        assertEquals(listOf("C", "Am", "F", "G"), Sheet.chords(lines).map { it.name })
    }

    @Test fun readsTheOtherShapeThePageComesIn() {
        val data = JSONObject().put(
            "store",
            JSONObject().put("page", JSONObject().put("template", JSONObject())).put(
                "tab",
                JSONObject().put("song_name", "Lantern Road").put("artist_name", "Ada Whitlock").put("type", "Chords")
                    .put("capo", 3).put("tuning", "D A D G B E").put("content", content),
            ),
        )
        val attr = data.toString().replace("&", "&amp;").replace("\"", "&quot;")
        val song = SongPage.stored("<div class=\"js-store\" data-content=\"$attr\"></div>", "https://tabs.example.com/tab/1")!!
        assertEquals("Lantern Road", song.title)
        assertEquals("Ada Whitlock", song.artist)
        assertEquals(3, song.capo)
        assertEquals("D A D G B E", song.tuning)
        assertEquals("https://tabs.example.com/tab/1", song.url)
        assertTrue(song.text.startsWith("[Intro]\nC   Am\n"))
    }

    @Test fun keepsAccentedLettersAsWritten() {
        assertEquals("É é Ñ ñ", Html.decode("&Eacute; &eacute; &Ntilde; &ntilde;"))
    }

    @Test fun aPageWithNoSheetAsTextIsNotASong() {
        assertNull(SongPage.stored(page(null, type = "Pro"), "https://tabs.example.com/x"))
        assertNull(SongPage.stored("<html><body>nothing</body></html>", "https://example.com"))
    }

    @Test fun theReaderFindsIt() {
        val found = Reader.fromHtml(page(content), "https://tabs.example.com/x")
        assertTrue(found is Reader.Result.Found)
    }

    @Test fun readsASheetSetAsPreformattedText() {
        val html = "<html><head><title>Lantern Road</title></head><body><pre>short</pre>" +
            "<pre>C              Am\nLantern on the gatepost\n<b>F</b>           G<br>Shows the road is long &amp; slow\n</pre></body></html>"
        val found = Reader.fromHtml(html, "https://example.com/lantern") as Reader.Result.Found
        assertEquals("Lantern Road", found.song.title)
        assertEquals("https://example.com/lantern", found.song.url)
        assertEquals("C              Am\nLantern on the gatepost\nF           G\nShows the road is long & slow\n", found.song.text)
    }

    @Test fun aPageWithNeitherGivesItsTitle() {
        val r = Reader.fromHtml("<html><head><title>About us</title></head><body><p>Hello</p></body></html>", "https://example.com")
        assertEquals(Reader.Result.NoSong("About us"), r)
    }

    @Test fun aBotCheckIsARefusal() {
        val r = Reader.fromHtml("<html><head><title>Just a moment...</title></head><body></body></html>", "https://example.com")
        assertEquals(Reader.Result.Failed(403), r)
    }

    @Test fun tellsALinkFromASheet() {
        assertTrue(Reader.isLinkShare("Lantern Road chords https://tabs.example.com/tab/1"))
        assertTrue(!Reader.isLinkShare("C  Am\nLantern on the gatepost\nF  G\nShows the road\nfrom https://example.com"))
        assertEquals("https://example.com/a", Reader.addressIn("see https://example.com/a."))
    }
}
