package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * The WebDAV calls themselves, over real HTTP, against a small server on this machine that
 * answers the way a Nextcloud does as far as a sync leans on it: listings with file ids and
 * etags, If-Match and If-None-Match honoured, a folder that has to exist before a file goes in
 * it, MOVE refusing to write over, and no way in without the password.
 */
class NextcloudFolderTest {

    private class Stored(val id: Int, var text: String, var etag: String)

    private val server = MockWebServer()
    private val folders = mutableSetOf("/remote.php/dav/files/ada/")
    private val files = linkedMapOf<String, Stored>()
    private var next = 100
    private fun tag() = "\"e${next++}\""

    private fun answer(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): MockResponse {
        val r = MockResponse().setResponseCode(code)
        headers.forEach { (k, v) -> r.addHeader(k, v) }
        if (body.isNotEmpty()) r.setBody(body)
        return r
    }

    private fun entry(path: String, etag: String, id: Int, folder: Boolean) =
        "<d:response><d:href>${encodePath(path)}</d:href><d:propstat><d:prop><d:getetag>$etag</d:getetag>" +
            "<d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><oc:fileid>$id</oc:fileid></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun handle(x: RecordedRequest): MockResponse {
        val path = URLDecoder.decode(x.requestUrl!!.encodedPath.replace("+", "%2B"), "UTF-8")
        val body = x.body.readUtf8()
        if (x.getHeader("Authorization") != okhttp3.Credentials.basic("ada", "app-password")) return answer(401)
        val ifMatch = x.getHeader("If-Match")
        return when (x.method) {
            "PROPFIND" -> {
                val out = StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
                if (path in folders) {
                    out.append(entry(path, "\"dir\"", 1, true))
                    if (x.getHeader("Depth") == "1") {
                        files.filterKeys { it.startsWith(path) && !it.removePrefix(path).contains('/') }.forEach { (p, f) -> out.append(entry(p, f.etag, f.id, false)) }
                    }
                } else {
                    val f = files[path] ?: return answer(404)
                    out.append(entry(path, f.etag, f.id, false))
                }
                answer(207, out.append("</d:multistatus>").toString())
            }
            "MKCOL" -> if (path in folders) answer(405) else if (path.trimEnd('/').substringBeforeLast('/') + "/" !in folders) answer(409) else { folders += path; answer(201) }
            "PUT" -> {
                val was = files[path]
                when {
                    path.substringBeforeLast('/') + "/" !in folders -> answer(409)
                    x.getHeader("If-None-Match") == "*" && was != null -> answer(412)
                    ifMatch != null && was?.etag != ifMatch -> answer(412)
                    else -> {
                        val f = was ?: Stored(next++, "", "").also { files[path] = it }
                        f.text = body
                        f.etag = tag()
                        answer(if (was == null) 201 else 204, headers = mapOf("OC-ETag" to f.etag))
                    }
                }
            }
            "GET" -> files[path]?.let { answer(200, it.text, mapOf("ETag" to it.etag)) } ?: answer(404)
            "MOVE" -> {
                val to = URLDecoder.decode(URI(x.getHeader("Destination")).rawPath.replace("+", "%2B"), "UTF-8")
                val f = files[path] ?: return answer(404)
                if (to in files) return answer(412)
                files.remove(path)
                files[to] = f
                answer(201)
            }
            "DELETE" -> {
                val f = files[path] ?: return answer(404)
                if (ifMatch != null && f.etag != ifMatch) return answer(412)
                files.remove(path)
                answer(204)
            }
            else -> answer(405)
        }
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) { runCatching { handle(request) }.getOrElse { answer(500) } }
        }
        server.start()
    }

    @After fun stop() { runCatching { server.shutdown() } }

    private fun account(password: String = "app-password") = Account("http://127.0.0.1:${server.port}", "ada", password)
    private fun folder() = NextcloudFolder(account(), "Music/Songbook")
    private val root = "/remote.php/dav/files/ada/Music/Songbook/"

    @Test fun theFirstListingMakesTheFolder() {
        assertEquals(Listing(emptyMap(), emptySet()), folder().list())
        assertTrue(root in folders && "/remote.php/dav/files/ada/Music/" in folders)
    }

    @Test fun createsReadsWritesRenamesAndDeletes() {
        val f = folder()
        f.list()
        val made = f.create("Lantern Road - Ada Whitlock", "one")
        assertEquals("Lantern Road - Ada Whitlock", made.name)
        assertEquals(".cho", made.ending)
        assertEquals(mapOf(made.fileId to made), f.list().songs)
        assertEquals("one" to made.etag, f.read(made))
        assertThrows(Taken::class.java) { f.create("Lantern Road - Ada Whitlock", "again") }

        val etag = f.write(made, "two")
        assertFalse(etag == made.etag)
        assertThrows(Changed::class.java) { f.write(made, "stale") }
        val now = made.copy(etag = etag)
        assertEquals("two", f.read(now).first)

        f.create("Other", "x")
        assertFalse(f.rename(now, "Other"))
        assertTrue(f.rename(now, "Lantern Lane & Co"))
        val listed = f.list()
        assertEquals(setOf("Lantern Lane & Co", "Other"), listed.names)
        assertEquals(now.fileId, listed.songs.values.first { it.name == "Lantern Lane & Co" }.fileId)

        val renamed = listed.songs.getValue(now.fileId)
        assertThrows(Changed::class.java) { f.delete(renamed.copy(etag = "\"old\"")) }
        f.delete(renamed)
        assertEquals(setOf("Other"), f.list().names)
    }

    @Test fun onlySongFilesAreListed() {
        folder().list()
        files[root + "notes.txt"] = Stored(7, "x", "\"n\"")
        files[root + "A Song.pro"] = Stored(8, "x", "\"p\"")
        val l = folder().list()
        assertEquals(setOf("A Song"), l.names)
        assertEquals(".pro", l.songs.getValue("8").ending)
    }

    @Test fun aWrongPasswordIsRefusedAndASilentServerUnreachable() {
        assertThrows(Refused::class.java) { NextcloudFolder(account("wrong"), "Songbook").probe() }
        folder().probe()
        server.shutdown()
        assertThrows(Unreachable::class.java) { folder().list() }
    }

    @Test fun aWholeSyncGoesOverTheWire() {
        val phone = SyncTest.Phone()
        phone.save(Song(id = "a", title = "Lantern Road", artist = "Ada Whitlock", text = "G    C\nLantern road\n", capo = 2))
        var links = emptyMap<String, Link>()
        fun sync() { Sync(folder(), phone, links, " (phone)").also { it.run(); links = it.links.toMap() } }
        sync()
        val path = root + "Lantern Road - Ada Whitlock.cho"
        assertTrue(files.getValue(path).text.contains("[G]Lante[C]rn road"))
        // A song put there by hand comes here; a change there comes here; a change here goes there.
        files[root + "Paper Boats.cho"] = Stored(next++, "{title: Paper Boats}\n[Am]Folded up the [F]morning news\n", tag())
        files.getValue(path).apply { text = text.replace("{capo: 2}", "{capo: 4}"); etag = tag() }
        sync()
        assertEquals(4, phone.all.getValue("a").capo)
        assertEquals(setOf("Lantern Road", "Paper Boats"), phone.songs().map { it.title }.toSet())
        phone.save(phone.all.getValue("a").copy(title = "Lantern Lane"))
        sync()
        assertTrue(root + "Lantern Lane - Ada Whitlock.cho" in files)
        assertFalse(path in files)
        // Deleted here: gone there.
        phone.all.remove("a")
        sync()
        assertEquals(setOf(root + "Paper Boats.cho"), files.keys)
    }
}
