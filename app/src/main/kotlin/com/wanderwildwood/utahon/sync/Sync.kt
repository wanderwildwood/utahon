package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the last sync left one song as: its id on this phone, where it is in the cloud and the
 * etag its file had then, and the song's own `modified` stamp here then.
 *
 * The links are also the record of deletions. A song with a link and no song here was deleted
 * on this phone; a link whose file has gone from the cloud was deleted there. A link is dropped
 * only once the other side has followed.
 */
data class Link(val id: String, val remote: RemoteSong, val modified: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fileId", remote.fileId)
        .put("name", remote.name)
        .put("ending", remote.ending)
        .put("etag", remote.etag)
        .put("modified", modified)

    companion object {
        fun fromJson(o: JSONObject) = Link(
            id = o.getString("id"),
            remote = RemoteSong(o.getString("fileId"), o.getString("name"), o.getString("ending"), o.getString("etag")),
            modified = o.getString("modified"),
        )

        fun listToJson(links: Collection<Link>): String = JSONArray().also { a -> links.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(text: String): Map<String, Link> {
            val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyMap()
            return (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }.associateBy { it.id }
        }
    }
}

/** The songs on this phone, as a sync sees them. [com.wanderwildwood.utahon.data.Store] is the real one. */
interface Local {
    fun songs(): List<Song>

    /**
     * [song] kept exactly as given, its stamps included, only if the song of its id here is
     * still at [expect] (its `modified`), or absent when [expect] is null. False, and nothing
     * done, when it was changed meanwhile: the next sync takes it from there.
     */
    fun put(song: Song, expect: String?): Boolean

    /** The song deleted, on the same condition. */
    fun remove(id: String, expect: String): Boolean

    fun newId(): String
    fun now(): String
}

/** One thing a sync does, as [plan] decides it. */
sealed interface Action {
    /** Changed here since the last sync, not there: sent. */
    data class Push(val link: Link, val remote: RemoteSong) : Action
    /** Changed there, not here (or deleted here, since a change beats a deletion): fetched. */
    data class Pull(val link: Link, val remote: RemoteSong) : Action
    /** Changed on both sides: both kept, the phone's under a new name, unless they now agree. */
    data class Conflict(val link: Link, val remote: RemoteSong) : Action
    /** Unchanged on both sides, but the file was renamed: noted. */
    data class Relink(val link: Link, val remote: RemoteSong) : Action
    /** Deleted there, unchanged here: deleted here. */
    data class DeleteHere(val link: Link) : Action
    /** Deleted here, unchanged there: deleted there. */
    data class DeleteThere(val link: Link, val remote: RemoteSong) : Action
    /** Gone from both sides: the link is let go. */
    data class Forget(val link: Link) : Action
    /** New here, or changed here after it was deleted there: sent as a new song. */
    data class Send(val id: String) : Action
    /** New there: fetched as a new song. */
    data class Fetch(val remote: RemoteSong) : Action
    /** New on both sides under the same name: one song if they agree, both kept if not. */
    data class Match(val id: String, val remote: RemoteSong) : Action
}

/**
 * What a sync has to do, from the songs here, the links the last sync left and what the cloud
 * holds now. A song has changed here when its `modified` stamp is not the one its link
 * recorded, and there when its file's etag is not.
 *
 * Nothing is lost on either side. A change beats a deletion, whichever side made which; changes
 * on both sides keep both. When every linked file has gone at once (the folder renamed or
 * swapped on the server, say), nothing is deleted here: the links are let go, and the songs are
 * matched by name or sent again.
 */
fun plan(locals: List<Song>, links: Map<String, Link>, listing: Listing): List<Action> {
    val here = locals.associateBy { it.id }
    val there = listing.songs
    val usable = if (links.size >= 3 && links.values.none { it.remote.fileId in there }) emptyMap() else links

    val out = mutableListOf<Action>()
    val linkedIds = mutableSetOf<String>()
    val linkedFiles = mutableSetOf<String>()
    for (link in usable.values) {
        val l = here[link.id]
        val r = there[link.remote.fileId]
        linkedIds += link.id
        if (r != null) linkedFiles += r.fileId
        val changedHere = l != null && l.modified != link.modified
        val changedThere = r != null && r.etag != link.remote.etag
        out += when {
            l == null && r == null -> Action.Forget(link)
            r == null -> if (changedHere) Action.Send(link.id) else Action.DeleteHere(link)
            l == null -> if (changedThere) Action.Pull(link, r) else Action.DeleteThere(link, r)
            changedHere && changedThere -> Action.Conflict(link, r)
            changedHere -> Action.Push(link, r)
            changedThere -> Action.Pull(link, r)
            r != link.remote -> Action.Relink(link, r)
            else -> continue
        }
    }

    val newThere = there.values.filter { it.fileId !in linkedFiles }.toMutableList()
    for (l in locals) {
        if (l.id in linkedIds) continue
        val name = SongFile.fileName(l)
        val same = newThere.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (same != null) {
            newThere -= same
            out += Action.Match(l.id, same)
        } else {
            out += Action.Send(l.id)
        }
    }
    newThere.forEach { out += Action.Fetch(it) }

    // Whatever frees or takes a file name there before anything new is sent.
    return out.sortedBy { if (it is Action.Send) 1 else 0 }
}

private val ID = Regex("[A-Za-z0-9_-]{1,80}")

/**
 * One sync between [local] and [remote], doing what [plan] decides. [phoneSuffix] goes after
 * the title of the phone's copy when both sides changed a song (" (phone)").
 *
 * A file that changed on the server between the listing and the write ([Changed]) is left for
 * the next sync, which sees it as changed and does the right thing then. Anything else that
 * fails ends the sync where it is; [links] holds what was done up to there, for the caller to
 * keep either way.
 */
class Sync(
    private val remote: Remote,
    private val local: Local,
    start: Map<String, Link>,
    private val phoneSuffix: String,
) {
    val links: MutableMap<String, Link> = start.toMutableMap()

    fun run() {
        val listing = remote.list()
        val actions = plan(local.songs(), links, listing)
        // Every link not dropped by the plan's own "all gone" rule is acted on by it; the rest go.
        val acted = actions.mapNotNull { linkOf(it)?.id }.toSet()
        val listed = listing.songs.keys
        links.values.removeAll { it.id !in acted && it.remote.fileId !in listed }
        val taken = listing.names.toMutableSet()
        for (a in actions) {
            try {
                act(a, taken)
            } catch (_: Changed) {
                // Left for the next sync.
            }
        }
    }

    private fun linkOf(a: Action): Link? = when (a) {
        is Action.Push -> a.link
        is Action.Pull -> a.link
        is Action.Conflict -> a.link
        is Action.Relink -> a.link
        is Action.DeleteHere -> a.link
        is Action.DeleteThere -> a.link
        is Action.Forget -> a.link
        else -> null
    }

    private fun act(a: Action, taken: MutableSet<String>) {
        val here = local.songs().associateBy { it.id }
        when (a) {
            is Action.Push -> {
                val mine = here[a.link.id] ?: return
                val etag = remote.write(a.remote.copy(etag = a.link.remote.etag), SongFile.toFile(mine))
                var r = a.remote.copy(etag = etag)
                links[mine.id] = Link(mine.id, r, mine.modified)
                // A new title or artist renames the file too, after the write.
                val wanted = SongFile.fileName(mine)
                if (wanted != r.name && taken.none { it.equals(wanted, ignoreCase = true) } && remote.rename(r, wanted)) {
                    taken -= r.name
                    taken += wanted
                    r = r.copy(name = wanted)
                    links[mine.id] = Link(mine.id, r, mine.modified)
                }
            }
            is Action.Pull -> {
                val (text, etag) = remote.read(a.remote)
                val theirs = SongFile.fromFile(text, a.remote.file) ?: return
                val kept = stamped(theirs.copy(id = a.link.id))
                if (local.put(kept, here[a.link.id]?.modified)) {
                    links[kept.id] = Link(kept.id, a.remote.copy(etag = etag), kept.modified)
                }
            }
            is Action.Conflict -> {
                val mine = here[a.link.id] ?: return
                val (text, etag) = remote.read(a.remote)
                val theirs = SongFile.fromFile(text, a.remote.file) ?: return
                if (SongFile.same(mine, theirs)) {
                    links[mine.id] = Link(mine.id, a.remote.copy(etag = etag), mine.modified)
                    return
                }
                // The phone's copy under a new title and id, sent as a new song; the server's
                // takes the old id, and with it the place in the list, the setlists and the link.
                val copy = mine.copy(id = local.newId(), title = mine.title + phoneSuffix, modified = local.now())
                if (!local.put(copy, null)) return
                if (local.put(stamped(theirs.copy(id = mine.id)), mine.modified)) {
                    val kept = local.songs().first { it.id == mine.id }
                    links[mine.id] = Link(mine.id, a.remote.copy(etag = etag), kept.modified)
                }
                send(copy, taken)
            }
            is Action.Relink -> links[a.link.id] = a.link.copy(remote = a.remote)
            is Action.DeleteHere -> {
                val l = here[a.link.id]
                if (l == null || local.remove(l.id, a.link.modified)) links.remove(a.link.id)
            }
            is Action.DeleteThere -> {
                remote.delete(a.remote.copy(etag = a.link.remote.etag))
                taken.removeAll { it.equals(a.remote.name, ignoreCase = true) }
                links.remove(a.link.id)
            }
            is Action.Forget -> links.remove(a.link.id)
            is Action.Send -> {
                links.remove(a.id)
                here[a.id]?.let { send(it, taken) }
            }
            is Action.Fetch -> {
                val (text, etag) = remote.read(a.remote)
                val theirs = SongFile.fromFile(text, a.remote.file) ?: return
                val id = theirs.id.takeIf { usable(it) && it !in here && it !in links } ?: local.newId()
                val kept = stamped(theirs.copy(id = id))
                if (local.put(kept, null)) links[id] = Link(id, a.remote.copy(etag = etag), kept.modified)
            }
            is Action.Match -> {
                val mine = here[a.id] ?: return
                val (text, etag) = remote.read(a.remote)
                val theirs = SongFile.fromFile(text, a.remote.file) ?: return
                if (SongFile.same(mine, theirs)) {
                    links[mine.id] = Link(mine.id, a.remote.copy(etag = etag), mine.modified)
                    return
                }
                // Two different songs of one name: the server's comes in as it is, and the
                // phone's goes up beside it under the phone's name.
                val newId = theirs.id.takeIf { usable(it) && it !in here && it !in links } ?: local.newId()
                val fetched = stamped(theirs.copy(id = newId))
                if (local.put(fetched, null)) links[newId] = Link(newId, a.remote.copy(etag = etag), fetched.modified)
                val renamed = mine.copy(title = mine.title + phoneSuffix, modified = local.now())
                if (local.put(renamed, mine.modified)) send(renamed, taken)
            }
        }
    }

    /** [s] sent as a new song, in a file of its name not already there. */
    private fun send(s: Song, taken: MutableSet<String>) {
        val text = SongFile.toFile(s)
        repeat(5) {
            val name = SongFile.unique(SongFile.fileName(s), taken)
            taken += name
            val made = try {
                remote.create(name, text)
            } catch (_: Taken) {
                return@repeat
            }
            links[s.id] = Link(s.id, made, s.modified)
            return
        }
    }

    /** A song from the server with both its dates, as this phone keeps every song. */
    private fun stamped(s: Song): Song {
        val modified = s.modified.ifEmpty { s.created }.ifEmpty { local.now() }
        return s.copy(modified = modified, created = s.created.ifEmpty { modified })
    }

    /** An id from a file kept as this phone's own only when it is the kind this app makes. */
    private fun usable(id: String) = ID.matches(id)
}
