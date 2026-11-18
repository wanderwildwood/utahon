package com.wanderwildwood.utahon.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginFlowTest {
    private fun started(login: String, poll: String) = LoginFlow.Started(login, poll, "t")
    private val server = "https://cloud.example.org"

    @Test fun httpsAddressesAreTakenAsTheyAre() {
        val s = started("https://cloud.example.org/login/v2/flow/x", "https://cloud.example.org/login/v2/poll")
        assertEquals(s, LoginFlow.checked(s, server))
        // A server behind a proxy may name itself by another host; over https that is taken.
        val other = started("https://nc.example.net/login/v2/flow/x", "https://nc.example.net/login/v2/poll")
        assertEquals(other, LoginFlow.checked(other, server))
    }

    @Test fun plainHttpOnTheTypedHostIsTakenAsHttps() {
        val s = LoginFlow.checked(started("http://cloud.example.org/login/v2/flow/x", "http://cloud.example.org:443/login/v2/poll"), server)!!
        assertEquals("https://cloud.example.org/login/v2/flow/x", s.loginUrl)
        assertEquals("https://cloud.example.org:443/login/v2/poll", s.pollUrl)
    }

    @Test fun anythingElseIsRefused() {
        assertNull(LoginFlow.checked(started("https://cloud.example.org/x", "http://elsewhere.example.net/poll"), server))
        assertNull(LoginFlow.checked(started("intent://cloud.example.org/x", "https://cloud.example.org/poll"), server))
        assertNull(LoginFlow.checked(started("javascript:alert(1)", "https://cloud.example.org/poll"), server))
    }
}
