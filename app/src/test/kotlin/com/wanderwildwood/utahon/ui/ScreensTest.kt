package com.wanderwildwood.utahon.ui

import com.wanderwildwood.utahon.data.Place
import com.wanderwildwood.utahon.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreensTest {
    @Test fun everyScreenComesBackAsItWas() {
        val draft = Song(id = "", title = "Lantern Road", artist = "Ada Whitlock", text = "C  Am\nLantern on the gatepost\n")
        val stack = listOf(
            Screen.List,
            Screen.Sheet("s1", Place("evening", 2)),
            Screen.Sheet("s2"),
            Screen.Set("evening"),
            Screen.Artist("Tomas Reyes"),
            Screen.Edit(null, draft, 7),
            Screen.Edit("s1"),
            Screen.Reading("https://tabs.example.com/x"),
            Screen.Reading_Many,
            Screen.Settings,
            Screen.Nextcloud,
        )
        assertEquals(stack, stack.map { Screens.decode(Screens.encode(it)) })
    }

    @Test fun aShareWaitingForItsYesComesBackAsItWas() {
        val waiting = listOf(
            Incoming.Link("https://tabs.example.com/lantern-road"),
            Incoming.Text("Paper Boats", "Am  F\nFolded up the morning news\n"),
            Incoming.Text("", ""),
        )
        assertEquals(waiting, waiting.map { Asking.decode(Asking.encode(it)) })
        assertNull(Asking.decode(emptyList()))
        assertNull(Asking.decode(listOf("elsewhere", "x")))
    }

    @Test fun somethingElseIsNoScreen() {
        assertNull(Screens.decode("not json"))
        assertNull(Screens.decode("{\"k\":\"elsewhere\"}"))
    }
}
