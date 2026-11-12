package com.wanderwildwood.utahon.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Place
import com.wanderwildwood.utahon.data.Setlist
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.data.Store
import com.wanderwildwood.utahon.importer.Batch
import com.wanderwildwood.utahon.importer.Reader
import com.wanderwildwood.utahon.importer.Reads
import com.wanderwildwood.utahon.importer.SongPage
import com.wanderwildwood.utahon.music.ChordPro
import com.wanderwildwood.utahon.sync.Cloud
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What another app handed this one: a page to read, a sheet as text, or a file of songs. */
sealed interface Incoming {
    data class Link(val url: String) : Incoming
    data class Text(val subject: String, val text: String) : Incoming
    data class File(val uri: Uri) : Incoming
}

/** The places in the app. Each opens over the one it was reached from, and Back closes it. */
sealed interface Screen {
    data object List : Screen
    /** A song; [place] is where it stands in the setlist it was opened from, if it was. */
    data class Sheet(val id: String, val place: Place? = null) : Screen
    data class Set(val id: String) : Screen

    /** The songs by one artist: a folder of the Artists tab. */
    data class Artist(val name: String) : Screen

    /** A song being typed in or changed: [id] null for a new one, [draft] what it starts from. */
    data class Edit(val id: String?, val draft: Song? = null, val note: Int = 0) : Screen
    data class Reading(val url: String) : Screen
    /** A list of pages being read one after another. */
    data object Reading_Many : Screen
    data object Settings : Screen
    data object Nextcloud : Screen
}

@Composable
fun SongbookApp(incoming: Incoming?, onTaken: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store.get(context) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.List)) }
    var about by remember { mutableStateOf(false) }

    fun go(s: Screen) { stack = stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }

    /** A page's address: the song already saved from it, or the page read. */
    fun forLink(url: String): Screen {
        val known = store.byUrl(url) ?: return Screen.Reading(url)
        Toast.makeText(context, context.getString(R.string.already_saved), Toast.LENGTH_SHORT).show()
        return Screen.Sheet(known.id)
    }

    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        onTaken()
        when (i) {
            is Incoming.Link -> stack = listOf(Screen.List, forLink(i.url))
            // Shared words (not just a link): a list of links is read page by page; anything else is a draft, to name and save.
            is Incoming.Text -> if (Reader.isLinkList(i.text)) {
                Batch.start(Reader.addressesIn(i.text), store)
                stack = listOf(Screen.List, Screen.Reading_Many)
            } else stack = listOf(Screen.List, Screen.Edit(null, draftFromText(i.subject, i.text), R.string.shared_draft_note))
            is Incoming.File -> {
                val opened = bringIn(context, store, i.uri)
                stack = listOf(Screen.List) + listOfNotNull(opened?.let { Screen.Sheet(it) })
            }
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }
    val cloud = remember { Cloud.get(context) }
    val account by cloud.account.collectAsStateWithLifecycle()

    when (val s = stack.last()) {
        Screen.List -> ListScreen(
            store = store,
            onOpen = { go(Screen.Sheet(it)) },
            onNew = { go(Screen.Edit(null)) },
            onLink = { urls ->
                if (urls.size == 1) go(forLink(urls[0])) else {
                    Batch.start(urls, store)
                    go(Screen.Reading_Many)
                }
            },
            onSettings = { go(Screen.Settings) },
            onSetlist = { go(Screen.Set(it)) },
            onArtist = { go(Screen.Artist(it)) },
            onPull = if (account != null) ({ cloud.syncAndWait() }) else null,
        )
        is Screen.Sheet -> SongScreen(
            store = store,
            id = s.id,
            place = s.place,
            onBack = ::back,
            onEdit = { go(Screen.Edit(s.id)) },
            // The next song takes this one's place, so Back from any song of a set is the set.
            onNext = { id, place -> replace(Screen.Sheet(id, place)) },
        )
        Screen.Reading_Many -> BatchScreen(onBack = ::back, onOpen = { go(Screen.Sheet(it)) })
        is Screen.Artist -> ArtistScreen(store = store, artist = s.name, onBack = ::back, onOpen = { go(Screen.Sheet(it)) })
        is Screen.Set -> SetlistScreen(store = store, id = s.id, onBack = ::back, onOpen = { id, place -> go(Screen.Sheet(id, place)) })
        is Screen.Edit -> EditScreen(
            store = store,
            id = s.id,
            draft = s.draft,
            note = s.note,
            onDone = { saved -> if (saved != null && s.id == null) replace(Screen.Sheet(saved)) else back() },
        )
        is Screen.Reading -> ReadingScreen(
            store = store,
            url = s.url,
            onSaved = { replace(Screen.Sheet(it)) },
            onTypeIn = { draft, note -> replace(Screen.Edit(null, draft, note)) },
            onBack = ::back,
        )
        Screen.Settings -> SettingsScreen(
            store = store,
            onBack = ::back,
            onAbout = { about = true },
            onOpen = { stack = listOf(Screen.List, Screen.Sheet(it)) },
            onNextcloud = { go(Screen.Nextcloud) },
        )
        Screen.Nextcloud -> NextcloudScreen(cloud = cloud, onBack = ::back)
    }
    if (about) AboutDialog(onDismiss = { about = false })
}

/** The draft for text that arrived by sharing: a ChordPro sheet read as one, anything else kept as it came. */
fun draftFromText(subject: String, text: String): Song {
    if (ChordPro.looksLike(text)) {
        val pro = ChordPro.read(text)
        return Song(id = "", title = pro.title.ifBlank { subject.trim() }, artist = pro.artist, capo = pro.capo, key = pro.key, text = pro.text)
    }
    return Song(id = "", title = subject.trim(), text = text.trim() + "\n")
}

/**
 * The songs a file holds: a songbook saved from here (with its setlists), a ChordPro file, or a
 * plain sheet of chords over words, which takes the file's name as its title.
 */
fun songsInFile(text: String, fileName: String): Pair<List<Song>, List<Setlist>> {
    val saved = Song.allIn(text)
    if (saved.isNotEmpty()) return saved to Setlist.allIn(text)
    val name = fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
    if (ChordPro.looksLike(text)) {
        val pro = ChordPro.read(text)
        return listOf(Song(id = "", title = pro.title.ifBlank { name }, artist = pro.artist, capo = pro.capo, key = pro.key, text = pro.text)) to emptyList()
    }
    if (SongPage.looksLikeSheet(text)) return listOf(Song(id = "", title = name, text = text.replace("\r\n", "\n").trimEnd() + "\n")) to emptyList()
    return emptyList<Song>() to emptyList()
}

/**
 * Songs from a file another app opened here, or the reader picked: brought in, said so, and the
 * id of the one song when there was only one, to open it.
 */
suspend fun bringIn(context: Context, store: Store, uri: Uri): String? {
    val (songs, lists) = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { songsInFile(it, name) } ?: (emptyList<Song>() to emptyList())
    }
    if (songs.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.import_none), Toast.LENGTH_LONG).show()
        return null
    }
    val before = store.all.value.map { it.id }.toSet()
    val count = withContext(Dispatchers.IO) {
        Setlists.get(context).bringIn(lists)
        store.bringIn(songs)
    }
    Toast.makeText(context, context.resources.getQuantityString(R.plurals.imported, count, count), Toast.LENGTH_SHORT).show()
    if (songs.size != 1) return null
    return songs[0].id.takeIf { it.isNotEmpty() && store.get(it) != null }
        ?: store.all.value.map { it.id }.firstOrNull { it !in before }
}

/** Reading a page shared here; it gives way to the song, or to the form to type it in. */
@Composable
private fun ReadingScreen(
    store: Store,
    url: String,
    onSaved: (String) -> Unit,
    onTypeIn: (Song, Int) -> Unit,
    onBack: () -> Unit,
) {
    var failed by remember(url) { mutableStateOf<Int?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        failed = null
        // The read goes on even if this screen is replaced before it ends (see Reads).
        val done = Reads.of(url, store).await()
        when (val r = done.result) {
            is Reader.Result.Found -> done.saved?.let(onSaved)
            is Reader.Result.NoSong -> onTypeIn(Song(id = "", title = r.title, url = url), R.string.no_song_on_page)
            is Reader.Result.Failed -> failed = r.code
        }
    }
    ReadingPage(url = url, failed = failed, onRetry = { attempt++ }, onTypeIn = { onTypeIn(Song(id = "", title = "", url = url), 0) }, onBack = onBack)
}
