package com.wanderwildwood.utahon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElsewhereTest {
    @Test fun aPageIsOpenedOverHttps() {
        assertEquals("https://tabs.example.com/lantern-road", Elsewhere.pageAddress("https://tabs.example.com/lantern-road"))
        // An address kept as it was shared, over plain http, is asked for over https.
        assertEquals("https://tabs.example.com/lantern-road", Elsewhere.pageAddress(" http://tabs.example.com/lantern-road "))
    }

    @Test fun anythingElseIsNoPage() {
        assertNull(Elsewhere.pageAddress(""))
        assertNull(Elsewhere.pageAddress("https://"))
        assertNull(Elsewhere.pageAddress("content://songs/lantern-road"))
        assertNull(Elsewhere.pageAddress("file:///sdcard/lantern-road.cho"))
    }
}
