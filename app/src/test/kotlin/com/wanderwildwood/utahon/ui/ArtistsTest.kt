package com.wanderwildwood.utahon.ui

import com.wanderwildwood.utahon.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistsTest {

    private fun song(title: String, artist: String) = Song(id = title, title = title, artist = artist, text = "G\nla\n")

    @Test fun groupsSongsByWhoTheyAreBy() {
        val all = listOf(
            song("Kettle Song", "Ada Whitlock"), song("Paper Boats", "Tomas Reyes"), song("Lantern Road", "ada whitlock "),
            song("Loose Leaf", ""), song("Northbound Freight", "The Harrow Lane Band"), song("Salt and Cedar", "Ada Whitlock"),
        )
        assertEquals(listOf("Ada Whitlock" to 3, "The Harrow Lane Band" to 1, "Tomas Reyes" to 1, "" to 1), artistsOf(all))
        assertTrue(artistsOf(emptyList()).isEmpty())
    }

    @Test fun oneArtistHoweverItIsTyped() {
        assertTrue(sameArtist("Bright Eyes", " bright eyes"))
        assertTrue(!sameArtist("Bright Eyes", "Conor Oberst"))
        assertTrue(sameArtist("", " "))
    }
}
