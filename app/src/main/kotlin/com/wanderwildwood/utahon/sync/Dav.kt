package com.wanderwildwood.utahon.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One song in the cloud: its file, named by its id on the server ([fileId], which stays the
 * same when the file is renamed), the file's [name] without its ending, the [ending] it has,
 * and its etag, which changes whenever the file does.
 */
data class RemoteSong(val fileId: String, val name: String, val ending: String, val etag: String) {
    val file: String get() = name + ending
}

/** The songs in the folder, by [RemoteSong.fileId], and the name (without ending) of every song file in it. */
data class Listing(val songs: Map<String, RemoteSong>, val names: Set<String>)

/** The file was not what this phone last saw: changed, or gone, since. */
class Changed(message: String) : IOException(message)

/** A file of that name is already there. */
class Taken(message: String) : IOException(message)

/** The far end of a sync: a folder of song files. [NextcloudFolder] is the real one. */
interface Remote {
    /** Every song file in the folder. */
    fun list(): Listing

    /** The file's text, and its etag as read. [Changed] if it has gone. */
    fun read(r: RemoteSong): Pair<String, String>

    /** A new file of that name (no ending) holding the song. [Taken] if one is already there. */
    fun create(name: String, text: String): RemoteSong

    /** The song written over [r]'s file, only if it is still as [r] saw it ([Changed] if not). Its new etag. */
    fun write(r: RemoteSong, text: String): String

    /** [r]'s file renamed to [to] (no ending); false, and nothing done, if [to] is taken. */
    fun rename(r: RemoteSong, to: String): Boolean

    /** [r]'s file deleted, only if it is still as [r] saw it ([Changed] if not). */
    fun delete(r: RemoteSong)

    /** A file of the app's own beside the songs (the setlists), by its whole name: its text and etag, or null when it is not there. */
    fun readOwn(file: String): Pair<String, String>?

    /**
     * The app's own file written, only if what is there is still at [etag], or only if nothing
     * is there when [etag] is null ([Changed] if not). Its new etag.
     */
    fun writeOwn(file: String, text: String, etag: String?): String
}

/**
 * The song folder in a Nextcloud, over WebDAV: `remote.php/dav/files/<user>/<folder>/`, one
 * ChordPro file per song. Nextcloud keeps an etag for every file, says the new one after a
 * write, honours If-Match and If-None-Match, and gives every file an id that a rename keeps,
 * which is everything this leans on.
 */
class NextcloudFolder(
    private val account: Account,
    folder: String,
    private val client: OkHttpClient = http,
) : Remote {

    private val home = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/"
    private val folder = folder.trim('/', ' ')
    private val root = home + encodePath(this.folder) + "/"

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    private fun file(name: String) = root + encodePath(name)

    override fun list(): Listing {
        val top = try {
            propfind(root, 1)
        } catch (_: Missing) {
            // The first sync to a folder not made yet.
            makeFolders()
            return Listing(emptyMap(), emptySet())
        }
        val rootPath = decodeHref(root)
        val songs = mutableMapOf<String, RemoteSong>()
        val names = mutableSetOf<String>()
        for (entry in top) {
            if (entry.isFolder || !entry.path.startsWith(rootPath)) continue
            val file = entry.path.removePrefix(rootPath)
            if (file.isEmpty() || file.contains('/') || !SongFile.isSong(file)) continue
            val name = file.substringBeforeLast('.')
            names += name
            val id = entry.fileId ?: continue
            val etag = entry.etag ?: continue
            songs[id] = RemoteSong(id, name, file.substring(name.length), etag)
        }
        return Listing(songs, names)
    }

    override fun read(r: RemoteSong): Pair<String, String> =
        call(request(file(r.file)).get().build()) { response ->
            if (response.code == 404) throw Changed("${r.file} is gone")
            check(response)
            val etag = response.header("ETag")?.let(::canonicalEtag) ?: r.etag
            response.body!!.string() to etag
        }

    override fun create(name: String, text: String): RemoteSong {
        val file = name + SongFile.EXTENSION
        val etag = try {
            put(file(file), text, ifMatch = null)
        } catch (_: Changed) {
            throw Taken(file)
        } catch (_: Missing) {
            // The song folder itself is gone from under it.
            makeFolders()
            put(file(file), text, ifMatch = null)
        }
        val self = propfind(file(file), 0).firstOrNull() ?: throw IOException("$file is not there after writing it")
        return RemoteSong(self.fileId ?: throw IOException("no id for $file"), name, SongFile.EXTENSION, self.etag ?: etag)
    }

    override fun write(r: RemoteSong, text: String): String = put(file(r.file), text, ifMatch = r.etag)

    private fun put(url: String, text: String, ifMatch: String?): String {
        val builder = request(url).put(text.toRequestBody(TEXT))
        if (ifMatch != null) builder.header("If-Match", ifMatch) else builder.header("If-None-Match", "*")
        return call(builder.build()) { response ->
            if (response.code == 412) throw Changed(url)
            if (response.code == 404 || response.code == 409) throw Missing()
            check(response)
            (response.header("OC-ETag") ?: response.header("ETag"))?.let(::canonicalEtag)
        } ?: propfind(url, 0).firstOrNull()?.etag ?: throw IOException("no etag for $url")
    }

    override fun rename(r: RemoteSong, to: String): Boolean {
        val request = request(file(r.file)).method("MOVE", null)
            .header("Destination", file(to + r.ending))
            .header("Overwrite", "F")
            .build()
        return call(request) { response ->
            if (response.code == 412) return@call false
            if (response.code == 404) throw Changed("${r.file} is gone")
            check(response)
            true
        }
    }

    override fun delete(r: RemoteSong) {
        call(request(file(r.file)).delete().header("If-Match", r.etag).build()) { response ->
            if (response.code == 412) throw Changed("${r.file} changed")
            if (response.code == 404) return@call
            check(response)
        }
    }

    override fun readOwn(file: String): Pair<String, String>? =
        call(request(file(file)).get().build()) { response ->
            if (response.code == 404) return@call null
            check(response)
            val etag = response.header("ETag")?.let(::canonicalEtag) ?: propfind(file(file), 0).firstOrNull()?.etag ?: ""
            response.body!!.string() to etag
        }

    override fun writeOwn(file: String, text: String, etag: String?): String = try {
        put(file(file), text, ifMatch = etag)
    } catch (_: Missing) {
        makeFolders()
        put(file(file), text, ifMatch = etag)
    }

    /** The song folder and every folder above it, each made where it is missing. */
    private fun makeFolders() {
        var sofar = home
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            sofar += encodePath(part) + "/"
            call(request(sofar).method("MKCOL", null).build()) { response ->
                // 405 is "already there".
                if (response.code != 201 && response.code != 405) check(response)
            }
        }
    }

    private fun propfind(url: String, depth: Int): List<Entry> {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth.toString()).build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            if (response.code != 207) throw IOException("${response.code} for PROPFIND")
            response.body!!.byteStream().use(::parseMultistatus)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }

    private fun check(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("${response.code}")
            else -> throw IOException("${response.code} for ${response.request.method}")
        }
    }

    /**
     * Ask the server once, before an account is kept: that these credentials open the user's
     * files. [Refused] or [Unreachable] when not.
     */
    fun probe() {
        try {
            propfind(home, 0)
        } catch (_: Missing) {
            throw Refused("no files for ${account.user}")
        }
    }

    /** Nothing at that path. Inside this file only. */
    private class Missing : IOException("not there")
}

/** One entry of a WebDAV listing. [path] is decoded; a folder's ends in "/". */
data class Entry(val path: String, val etag: String?, val isFolder: Boolean, val fileId: String?)

/** An etag as a header carries it, quoted; some servers list it bare. */
fun canonicalEtag(etag: String): String {
    val t = etag.trim()
    return if (t.startsWith("\"") || t.startsWith("W/\"")) t else "\"$t\""
}

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, with the properties asked
 * for that the server has. Read with the platform's own XML parser, which works the same off
 * the phone, where the tests run.
 */
fun parseMultistatus(body: InputStream): List<Entry> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<Entry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        var etag: String? = null
        var folder = false
        var fileId: String? = null
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            if (propstat.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
            propstat.first(DAV, "getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { etag = canonicalEtag(it) }
            propstat.first(OC, "fileid")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { fileId = it }
            if (propstat.first(DAV, "resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        var path = decodeHref(href)
        if (folder && !path.endsWith("/")) path += "/"
        out += Entry(path, etag, folder, fileId)
    }
    return out
}

/** The path an href names, decoded; a full URL or only a path both come out as the path. */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(Regex("/{2,}"), "/")
}

/** Each segment of a path percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private const val DAV = "DAV:"
private const val OC = "http://owncloud.org/ns"
private val TEXT = "text/plain; charset=utf-8".toMediaType()
private val XML = "application/xml; charset=utf-8".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><d:getetag/><d:resourcetype/><oc:fileid/></d:prop></d:propfind>"""

private fun Element.first(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }
