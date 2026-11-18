package com.wanderwildwood.utahon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.Collator
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Where the songs are kept: one small JSON file each, in the app's own storage. A file a person
 * could read, and nothing to migrate. A few hundred songs are a few hundred kilobytes, so all
 * of them are held in memory.
 */
class Store private constructor(context: Context) {

    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    /**
     * Ids of song files found at start that could not be read. A sync never takes them for
     * deleted, and brings the server's copy back over them; the damaged file is kept beside it
     * as `<id>.json.bad`.
     */
    @Volatile
    var unreadable: Set<String> = emptySet()
        private set

    /** False when the song folder could not be looked into at start: a sync then deletes nothing in the cloud. */
    @Volatile
    var listed: Boolean = true
        private set

    private val _all = MutableStateFlow(load())

    /**
     * Called after every change made here on the phone (a save, a setting of key or speed, a
     * deletion, songs brought in), not after a sync's own; the sync uses it to follow a change a
     * few seconds later.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Every song, in order of title, then of who it is by. */
    val all: StateFlow<List<Song>> = _all

    private fun load(): List<Song> {
        val bad = mutableSetOf<String>()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        listed = files != null
        val songs = files.orEmpty().mapNotNull { f ->
            val song = runCatching { Song.allIn(f.readText()).firstOrNull() }.getOrNull()
                ?.let { if (it.id.isEmpty()) it.copy(id = f.name.removeSuffix(".json")) else it }
            if (song == null) bad += f.name.removeSuffix(".json")
            song
        }
        unreadable = bad
        return sorted(songs)
    }

    private fun sorted(list: List<Song>) = list.sortedWith { a, b ->
        val t = collator.compare(a.title, b.title)
        if (t != 0) t else collator.compare(a.artist, b.artist)
    }

    fun get(id: String): Song? = _all.value.firstOrNull { it.id == id }

    /** The song already saved from [url], if one was. */
    fun byUrl(url: String): Song? =
        url.takeIf { it.isNotBlank() }?.let { u -> _all.value.firstOrNull { same(it.url, u) } }

    private fun same(a: String, b: String): Boolean {
        fun bare(s: String) = s.trim().substringBefore('#').substringBefore('?').removeSuffix("/")
            .replaceFirst(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "").lowercase()
        return a.isNotBlank() && bare(a) == bare(b)
    }

    /**
     * Keeps [song], new or changed, and gives it back as kept: with an id if it had none,
     * stamped with when it was made and changed. The file is written whole and then moved over
     * the old one, so a phone that dies mid-save keeps the old song rather than half of each.
     */
    @Synchronized
    fun save(song: Song): Song {
        val now = stampAfter(song.id.takeIf { it.isNotEmpty() }?.let { get(it)?.modified })
        val kept = song.copy(
            id = song.id.ifEmpty { newId() },
            created = song.created.ifEmpty { now },
            modified = now,
        )
        write(kept)
        _all.value = sorted(_all.value.filterNot { it.id == kept.id } + kept)
        onChanged?.invoke()
        return kept
    }

    /**
     * An edit of a song opened when it was at [base] (its `modified` then). If a sync has
     * changed the song since, the edit is kept as a copy beside it under [copySuffix], as a sync
     * conflict keeps both, and neither is written over. Returns the song as kept.
     */
    @Synchronized
    fun saveEdit(song: Song, base: String, copySuffix: String): Song {
        val now = song.id.takeIf { it.isNotEmpty() }?.let { get(it) }
        if (editedMeanwhile(now?.modified, base)) {
            return save(song.copy(id = "", created = "", title = song.title + copySuffix))
        }
        return save(song)
    }

    /**
     * How the reader has set a song (its key, its scroll speed). The stamp moves with it, so a
     * sync carries the setting to the reader's other devices.
     */
    @Synchronized
    fun set(id: String, change: (Song) -> Song) {
        val was = get(id) ?: return
        val changed = change(was).copy(id = was.id, modified = was.modified)
        if (changed == was) return
        val kept = changed.copy(modified = stampAfter(was.modified))
        write(kept)
        _all.value = _all.value.map { if (it.id == id) kept else it }
        onChanged?.invoke()
    }

    /**
     * A song from a sync, kept exactly as it came, stamps and all, only if the song of its id
     * here is still the one the sync saw: at [expect], or not here at all when that is null.
     */
    @Synchronized
    fun putSynced(song: Song, expect: String?): Boolean {
        if (get(song.id)?.modified != expect) return false
        write(song)
        _all.value = sorted(_all.value.filterNot { it.id == song.id } + song)
        return true
    }

    /** A song deleted by a sync, on the same condition. */
    @Synchronized
    fun removeSynced(id: String, expect: String): Boolean {
        val now = get(id) ?: return true
        if (now.modified != expect) return false
        File(dir, "${safe(id)}.json").delete()
        _all.value = _all.value.filterNot { it.id == id }
        return true
    }

    /**
     * The file written whole and flushed to the disk before it is moved over the old one. One
     * that could not be read at start is first moved aside, not written over.
     */
    private fun write(song: Song) {
        val file = File(dir, "${safe(song.id)}.json")
        val tmp = File(dir, "${safe(song.id)}.json.tmp")
        if (song.id in unreadable) {
            val bad = File(dir, "${safe(song.id)}.json.bad")
            bad.delete()
            file.renameTo(bad)
            unreadable = unreadable - song.id
        }
        FileOutputStream(tmp).use { out ->
            out.write(song.toJson().toString(1).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "${safe(id)}.json").delete()
        _all.value = _all.value.filterNot { it.id == id }
        onChanged?.invoke()
    }

    /**
     * Songs brought in from a file. One with the id of a song already here replaces it, which
     * is what bringing back a saved songbook should do; one with no id, or an id not seen
     * before, is added. Returns how many came in.
     */
    @Synchronized
    fun bringIn(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val now = now()
        val byId = _all.value.associateBy { it.id }.toMutableMap()
        for (s in songs) {
            val kept = s.copy(
                id = s.id.takeIf { it.isNotEmpty() && safe(it) == it } ?: newId(),
                created = s.created.ifEmpty { now },
                modified = s.modified.ifEmpty { now },
            )
            write(kept)
            byId[kept.id] = kept
        }
        _all.value = sorted(byId.values.toList())
        onChanged?.invoke()
        return songs.size
    }

    /** Every song as one document, for a file, with [setlists] beside them. */
    fun export(setlists: List<Setlist> = emptyList()): String = songbookDocument(_all.value, setlists).toString(1)

    fun newId() = UUID.randomUUID().toString()

    /** An id as a file name: letters, digits and dashes, as every id this app makes is. */
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    /**
     * Now, as a stamp that differs from [previous]: stamps are to the second, and two changes in
     * one second would otherwise look like none to a sync.
     */
    private fun stampAfter(previous: String?): String {
        val now = now()
        if (now != previous) return now
        return runCatching { OffsetDateTime.parse(now).plusSeconds(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrDefault(now)
    }

    fun now(): String =
        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        @Volatile
        private var store: Store? = null

        fun get(context: Context): Store =
            store ?: synchronized(this) { store ?: Store(context.applicationContext).also { store = it } }
    }
}

/** Whether a song opened at [base] has been changed since (it is now at [current]); a new song has no base. */
internal fun editedMeanwhile(current: String?, base: String): Boolean =
    current != null && base.isNotEmpty() && current != base

