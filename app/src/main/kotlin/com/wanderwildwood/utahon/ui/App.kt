package com.wanderwildwood.utahon.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
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
import kotlinx.coroutines.launch
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

/**
 * The screens open, kept when the window is built again (or the process let go) so a song
 * being typed comes back with its screen: each as one line of JSON.
 */
internal object Screens {
    fun encode(s: Screen): String {
        val o = org.json.JSONObject()
        when (s) {
            Screen.List -> o.put("k", "list")
            is Screen.Sheet -> {
                o.put("k", "sheet").put("id", s.id)
                s.place?.let { o.put("set", it.setlist).put("at", it.at) }
            }
            is Screen.Set -> o.put("k", "set").put("id", s.id)
            is Screen.Artist -> o.put("k", "artist").put("name", s.name)
            is Screen.Edit -> {
                o.put("k", "edit").put("note", s.note)
                s.id?.let { o.put("id", it) }
                s.draft?.let { o.put("draft", it.toJson()) }
            }
            is Screen.Reading -> o.put("k", "reading").put("url", s.url)
            Screen.Reading_Many -> o.put("k", "many")
            Screen.Settings -> o.put("k", "settings")
            Screen.Nextcloud -> o.put("k", "nextcloud")
        }
        return o.toString()
    }

    fun decode(text: String): Screen? = runCatching {
        val o = org.json.JSONObject(text)
        when (o.getString("k")) {
            "list" -> Screen.List
            "sheet" -> Screen.Sheet(o.getString("id"), if (o.has("set")) Place(o.getString("set"), o.getInt("at")) else null)
            "set" -> Screen.Set(o.getString("id"))
            "artist" -> Screen.Artist(o.getString("name"))
            "edit" -> Screen.Edit(
                if (o.has("id")) o.getString("id") else null,
                o.optJSONObject("draft")?.let { Song.fromJson(it) },
                o.optInt("note", 0),
            )
            "reading" -> Screen.Reading(o.getString("url"))
            "many" -> Screen.Reading_Many
            "settings" -> Screen.Settings
            "nextcloud" -> Screen.Nextcloud
            else -> null
        }
    }.getOrNull()

    val saver = androidx.compose.runtime.saveable.Saver<List<Screen>, ArrayList<String>>(
        save = { list -> ArrayList(list.map(::encode)) },
        restore = { saved -> saved.mapNotNull(::decode).ifEmpty { listOf(Screen.List) } },
    )
}

@Composable
fun SongbookApp(incoming: Incoming?, onTaken: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store.get(context) }
    var stack by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = Screens.saver) { mutableStateOf(listOf<Screen>(Screen.List)) }
    var about by remember { mutableStateOf(false) }

    fun go(s: Screen) { stack = stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }

    /** A page's address: the song already saved from it, or the page read. */
    fun forLink(url: String): Screen {
        val known = store.byUrl(url) ?: return Screen.Reading(url)
        Notice.say(context, R.string.already_saved)
        return Screen.Sheet(known.id)
    }

    // Something handed in by another app waits for a yes: it reads pages, brings songs in and
    // takes the place of the screen that is open, so nothing happens until it is asked for.
    var asking by remember { mutableStateOf<Incoming?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        onTaken()
        asking = i
    }

    suspend fun take(i: Incoming) {
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

    asking?.let { i ->
        IncomingDialog(
            incoming = i,
            onCancel = { asking = null },
            onConfirm = {
                asking = null
                scope.launch { take(i) }
            },
        )
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
/** Larger than any songbook of text; a file past it is said to hold no songs. */
private const val MAX_FILE_BYTES = 16 * 1024 * 1024

suspend fun bringIn(context: Context, store: Store, uri: Uri): String? {
    val (songs, lists) = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        // A songbook is text: a file far bigger than any is not read into memory at all.
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    if (bytes.size() > MAX_FILE_BYTES) throw java.io.IOException("too large")
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()?.let { songsInFile(it, name) } ?: (emptyList<Song>() to emptyList())
    }
    if (songs.isEmpty()) {
        Notice.say(context, R.string.import_none)
        return null
    }
    val before = store.all.value.map { it.id }.toSet()
    val count = withContext(Dispatchers.IO) {
        Setlists.get(context).bringIn(lists)
        store.bringIn(songs)
    }
    Notice.say(context.resources.getQuantityString(R.plurals.imported, count, count))
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

/** The question asked before anything handed in by another app is acted on. */
@Composable
private fun IncomingDialog(incoming: Incoming, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val question = when (incoming) {
        is Incoming.Link -> stringResource(
            R.string.incoming_link,
            runCatching { Uri.parse(incoming.url).host }.getOrNull() ?: incoming.url,
        )
        is Incoming.Text -> if (Reader.isLinkList(incoming.text)) {
            val n = Reader.addressesIn(incoming.text).size
            pluralStringResource(R.plurals.incoming_links, n, n)
        } else {
            stringResource(R.string.incoming_text)
        }
        is Incoming.File -> stringResource(R.string.incoming_file)
    }
    val confirm = stringResource(
        when (incoming) {
            is Incoming.File -> R.string.incoming_bring_in
            is Incoming.Text -> if (Reader.isLinkList(incoming.text)) R.string.incoming_read else R.string.incoming_start
            is Incoming.Link -> R.string.incoming_read
        },
    )
    EInkDialog(onDismiss = onCancel) {
        TextMMD(text = question, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        DialogButtons(
            cancel = stringResource(R.string.cancel),
            confirm = confirm,
            onCancel = onCancel,
            onConfirm = onConfirm,
            confirmOn = true,
        )
    }
}

