package com.wanderwildwood.utahon.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The login name signs in; the user id is what the paths use. Asked of the server once at
 * sign-in, kept with the account, and the login name where there is none.
 */
class UserIdTest {

    private val server = MockWebServer()

    @After fun stop() { runCatching { server.shutdown() } }

    private fun account() = Account("http://127.0.0.1:${server.port}", "Ada@example.org", "pw")

    @Test fun theServerSaysTheUserId() {
        server.enqueue(MockResponse().setBody("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"ada","display-name":"Ada"}}}"""))
        server.start()
        assertEquals("ada", userId(account()))
        val asked = server.takeRequest()
        assertEquals("/ocs/v2.php/cloud/user?format=json", asked.path)
        assertEquals("true", asked.getHeader("OCS-APIRequest"))
        assertEquals(okhttp3.Credentials.basic("Ada@example.org", "pw", Charsets.UTF_8), asked.getHeader("Authorization"))
    }

    @Test fun noIdFromTheServerIsTheLoginName() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("not json"))
        server.start()
        assertEquals("Ada@example.org", userId(account()))
        assertEquals("Ada@example.org", userId(account()))
    }

    @Test fun aWrongPasswordIsRefused() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        assertThrows(Refused::class.java) { userId(account()) }
    }

    @Test fun theUserIdIsKeptWithTheAccount() {
        val a = Account("https://cloud.example.org", "Ada@example.org", "pw", "ada")
        assertEquals(a, Account.fromJson(JSONObject(a.toJson().toString())))
    }

    @Test fun anAccountKeptBeforeHasTheLoginNameAsItsUserId() {
        val old = JSONObject().put("server", "https://cloud.example.org").put("user", "ada").put("password", "pw")
        assertEquals("ada", Account.fromJson(old).uid)
    }

    @Test fun theFilesAreUnderTheUserIdNotTheLoginName() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/ada/</d:href>""" +
                """<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
        ))
        server.start()
        NextcloudFolder(account().copy(uid = "ada"), "Songbook").probe()
        val asked = server.takeRequest()
        assertEquals("/remote.php/dav/files/ada/", asked.path)
        assertEquals(okhttp3.Credentials.basic("Ada@example.org", "pw", Charsets.UTF_8), asked.getHeader("Authorization"))
    }
}
