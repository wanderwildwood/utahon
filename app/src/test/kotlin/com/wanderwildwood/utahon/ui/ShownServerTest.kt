package com.wanderwildwood.utahon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The server is shown one way, for Home's account and the app's own. */
class ShownServerTest {
    @Test fun withoutTheSchemeOrTheSchemesOwnPort() {
        assertEquals("cloud.example.org", shownServer("https://cloud.example.org"))
        assertEquals("cloud.example.org", shownServer("https://cloud.example.org:443/"))
        assertEquals("10.0.2.2", shownServer("http://10.0.2.2:80"))
        assertEquals("cloud.example.org/nextcloud", shownServer("HTTPS://cloud.example.org/nextcloud/"))
    }

    @Test fun anotherPortStays() {
        assertEquals("cloud.example.org:8443", shownServer("https://cloud.example.org:8443"))
        assertEquals("10.0.2.2:8080", shownServer("http://10.0.2.2:8080"))
    }
}
