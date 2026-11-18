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
 * same when the file is renamed or moved), the [folder] it is in (its artist's, or none for a
 * song that names no artist), the file's [name] without its ending, the [ending] it has, and its
 * etag, which changes whenever the file does.
 */
data class RemoteSong(val fileId: String, val folder: String, val name: String, val ending: String, val etag: String) {
    val file: String get() = name + ending

    /** Where it is under the song folder, without its ending: "Ada Whitlock/Lantern Road". */
    val place: String get() = placeOf(folder, name)
}

/** A song's place under the song folder: its folder and its name, or the name alone. */
fun placeOf(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

/**
 * The songs in the folder, by [RemoteSong.fileId], and the [RemoteSong.place] of every song file
 * in it. [made] when the folder was not there and had to be made.
 */
data class Listing(val songs: Map<String, RemoteSong>, val places: Set<String>, val made: Boolean = false)

/** The file was not what this phone last saw: changed, or gone, since. */
class Changed(message: String) : IOException(message)

/** A file of that name is already there. */
class Taken(message: String) : IOException(message)

/** The far end of a sync: a folder of song files. [NextcloudFolder] is the real one. */
interface Remote {
    /** Every song file in the folder and in the folders one level inside it. */
    fun list(): Listing

    /** The file's text, and its etag as read. [Changed] if it has gone. */
    fun read(r: RemoteSong): Pair<String, String>

    /** A new file of that name (no ending) in [folder], made if it is not there, holding the song. [Taken] if one is already there. */
    fun create(folder: String, name: String, text: String): RemoteSong

    /** The song written over [r]'s file, only if it is still as [r] saw it ([Changed] if not). Its new etag. */
    fun write(r: RemoteSong, text: String): String

    /**
     * [r]'s file moved to [folder] (made if it is not there) under the name [to] (no ending);
     * false, and nothing done, if that place is taken. A folder the move leaves empty goes.
     */
    fun move(r: RemoteSong, folder: String, to: String): Boolean

    /** [r]'s file deleted, only if it is still as [r] saw it ([Changed] if not); and its folder, if that leaves it empty. */
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
 * ChordPro file per song, each in a folder named for its artist. Nextcloud keeps an etag for every file, says the new one after a
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
    private fun dir(folder: String) = root + encodePath(folder) + "/"
    private fun file(r: RemoteSong) = file(if (r.folder.isEmpty()) r.file else r.folder + "/" + r.file)

    override fun list(): Listing {
        val top = try {
            propfind(root, 1)
        } catch (_: Missing) {
            // The first sync to a folder not made yet, or one renamed or taken away on the server.
            makeFolders()
            return Listing(emptyMap(), emptySet(), made = true)
        }
        val rootPath = decodeHref(root)
        val songs = mutableMapOf<String, RemoteSong>()
        val places = mutableSetOf<String>()
        fun take(entries: List<Entry>, under: String, folder: String) {
            for (entry in entries) {
                if (entry.isFolder || !entry.path.startsWith(under)) continue
                val file = entry.path.removePrefix(under)
                if (file.isEmpty() || file.contains('/') || !SongFile.isSong(file)) continue
                val name = file.substringBeforeLast('.')
                places += placeOf(folder, name)
                val id = entry.fileId ?: continue
                val etag = entry.etag ?: continue
                songs[id] = RemoteSong(id, folder, name, file.substring(name.length), etag)
            }
        }
        take(top, rootPath, "")
        // The artists' folders, one level in; anything deeper is not this app's.
        for (entry in top) {
            if (!entry.isFolder || !entry.path.startsWith(rootPath)) continue
            val folder = entry.path.removePrefix(rootPath).trim('/')
            if (folder.isEmpty() || folder.contains('/') || folder.startsWith(".")) continue
            val inside = try {
                propfind(dir(folder), 1)
            } catch (_: Missing) {
                continue
            }
            take(inside, decodeHref(dir(folder)), folder)
        }
        return Listing(songs, places)
    }

    override fun read(r: RemoteSong): Pair<String, String> =
        call(request(file(r)).get().build()) { response ->
            if (response.code == 404) throw Changed("${r.place} is gone")
            check(response)
            val etag = response.header("ETag")?.let(::canonicalEtag) ?: r.etag
            response.body!!.string() to etag
        }

    override fun create(folder: String, name: String, text: String): RemoteSong {
        val made = RemoteSong("", folder, name, SongFile.EXTENSION, "")
        if (folder.isNotEmpty()) makeFolder(folder)
        val etag = try {
            put(file(made), text, ifMatch = null)
        } catch (_: Changed) {
            throw Taken(made.place)
        } catch (_: Missing) {
            // The song folder itself is gone from under it.
            makeFolders()
            if (folder.isNotEmpty()) makeFolder(folder)
            put(file(made), text, ifMatch = null)
        }
        val self = propfind(file(made), 0).firstOrNull() ?: throw IOException("${made.place} is not there after writing it")
        return made.copy(fileId = self.fileId ?: throw IOException("no id for ${made.place}"), etag = self.etag ?: etag)
    }

    override fun write(r: RemoteSong, text: String): String = put(file(r), text, ifMatch = r.etag)

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

    override fun move(r: RemoteSong, folder: String, to: String): Boolean {
        if (folder.isNotEmpty()) makeFolder(folder)
        val request = request(file(r)).method("MOVE", null)
            .header("Destination", file(r.copy(folder = folder, name = to)))
            .header("Overwrite", "F")
            .build()
        val moved = call(request) { response ->
            if (response.code == 412) return@call false
            if (response.code == 404) throw Changed("${r.place} is gone")
            check(response)
            true
        }
        if (moved && r.folder != folder) dropIfEmpty(r.folder)
        return moved
    }

    override fun delete(r: RemoteSong) {
        call(request(file(r)).delete().header("If-Match", r.etag).build()) { response ->
            if (response.code == 412) throw Changed("${r.place} changed")
            if (response.code == 404) return@call
            check(response)
        }
        dropIfEmpty(r.folder)
    }

    /** An artist's folder made where it is missing. */
    private fun makeFolder(folder: String) {
        call(request(dir(folder)).method("MKCOL", null).build()) { response ->
            // 405 is "already there".
            if (response.code != 201 && response.code != 405) check(response)
        }
    }

    /**
     * An artist's folder with nothing left in it is taken away; one with anything at all in it is
     * left. A DELETE of a folder takes everything in it, so it goes only while the folder is as it
     * was listed empty: its etag changes when anything is put in it, and a folder that has had a
     * song put in it since, from another phone, is then refused (412) and stays.
     */
    private fun dropIfEmpty(folder: String) {
        if (folder.isEmpty()) return
        val left = try {
            propfind(dir(folder), 1)
        } catch (_: IOException) {
            return
        }
        val self = decodeHref(dir(folder))
        if (left.any { it.path != self }) return
        val etag = left.firstOrNull { it.path == self }?.etag ?: return
        runCatching { call(request(dir(folder)).delete().header("If-Match", etag).build()) { } }
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

/**
 * An etag in one form, however it came: quoted, as a listing gives it. Some servers list it
 * bare; and a web server that compresses a file on its way out marks the etag it sends with it
 * as weak ("W/") or tacks "-gzip" on inside the quotes, so the same file's etag read with the
 * file is not the one its listing gives. Left as it came, a file just read looked changed at
 * the next sync, every time (found on a real Nextcloud, 11 Nov 2026).
 */
fun canonicalEtag(etag: String): String {
    var t = etag.trim().removePrefix("W/").trim('"')
    for (mark in listOf("-gzip", "--gzip", "-br", "-deflate", ";gzip")) t = t.removeSuffix(mark)
    return "\"$t\""
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
