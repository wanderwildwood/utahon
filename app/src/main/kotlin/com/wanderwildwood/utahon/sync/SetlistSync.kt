package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Setlist
import org.json.JSONArray
import org.json.JSONObject

/** What the last sync left the setlists as: the etag their file had then, and this phone's stamp for them then. */
data class ListsLink(val etag: String, val stamp: String)

/** The setlists on this phone, as a sync sees them. [com.wanderwildwood.utahon.data.Setlists] is the real one. */
interface LocalLists {
    fun lists(): List<Setlist>

    /** Changes whenever the setlists are changed here. */
    fun stamp(): String

    /** [lists] kept in place of what is here, only if the stamp is still [expect]; the new stamp, or null when it was not. */
    fun put(lists: List<Setlist>, expect: String): String?

    fun newId(): String
}

/**
 * The setlists kept in step with the cloud: all of them in one small file beside the songs,
 * since a setlist is only names and an order. Songs keep their ids from one device to the next,
 * so a list made on one opens the same songs on another.
 *
 * Changed on one side, the other takes them. Changed on both, the two are put together: a list
 * only one side has is kept, and a list both sides changed differently is kept twice, the
 * phone's as "… (phone)". Nothing is deleted by a sync that both sides did not agree to.
 */
object SetlistSync {

    const val FILE = "Setlists.json"

    fun toFile(lists: List<Setlist>): String =
        JSONObject().put("setlists", JSONArray().also { a -> lists.forEach { a.put(it.toJson()) } }).toString(1)

    /** The setlists in the server's file; null when it cannot be read as one, which is not the same as none. */
    fun fromFile(text: String): List<Setlist>? = runCatching {
        if (JSONObject(text.trim().removePrefix("\uFEFF")).has("setlists")) Setlist.allIn(text) else null
    }.getOrNull()

    /** One sync of the setlists; the link to keep for next time (null: there is still nothing to link). */
    fun run(remote: Remote, local: LocalLists, link: ListsLink?, phoneSuffix: String): ListsLink? {
        val mine = local.lists()
        val stamp = local.stamp()
        val there = remote.readOwn(FILE)
        try {
            if (there == null) {
                // Nothing there (yet, or any more): what is here goes up, if there is anything.
                if (mine.isEmpty()) return null
                return ListsLink(remote.writeOwn(FILE, toFile(mine), null), stamp)
            }
            val (text, etag) = there
            // A file there that cannot be read leaves both sides as they are.
            val theirs = fromFile(text) ?: return link
            val changedHere = if (link == null) mine.isNotEmpty() else stamp != link.stamp
            val changedThere = link == null || etag != link.etag
            return when {
                changedHere && changedThere -> {
                    val both = merged(mine, theirs, phoneSuffix, local::newId)
                    val kept = local.put(both, stamp) ?: return link
                    if (both == theirs) ListsLink(etag, kept) else ListsLink(remote.writeOwn(FILE, toFile(both), etag), kept)
                }
                changedHere -> ListsLink(remote.writeOwn(FILE, toFile(mine), etag), stamp)
                changedThere -> local.put(theirs, stamp)?.let { ListsLink(etag, it) } ?: link
                else -> link
            }
        } catch (_: Changed) {
            // The file moved under the write: left for the next sync.
            return link
        }
    }

    /** The two sides put together; see [SetlistSync]. The server's order first, then what only the phone has. */
    fun merged(mine: List<Setlist>, theirs: List<Setlist>, phoneSuffix: String, newId: () -> String): List<Setlist> {
        val out = theirs.toMutableList()
        val byId = theirs.associateBy { it.id }
        for (m in mine) {
            val t = byId[m.id]
            when {
                t == null -> out += m
                t == m -> Unit
                // The same list once the phone's copy was already made by an earlier sync.
                theirs.any { it.name == m.name + phoneSuffix && it.songs == m.songs } -> Unit
                else -> out += m.copy(id = newId(), name = m.name + phoneSuffix)
            }
        }
        return out
    }
}
