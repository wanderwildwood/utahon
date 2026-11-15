package com.wanderwildwood.utahon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.data.Store
import com.wanderwildwood.utahon.importer.Reader
import com.wanderwildwood.utahon.sync.Cloud
import com.wanderwildwood.utahon.sync.Pulled
import com.wanderwildwood.utahon.sync.pulledAfter
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.Normalizer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Every song, by title, and the way in to a new one. A search over titles, artists and words
 * opens under the bar when the magnifier is pressed; the + offers saving one from a page's
 * address or typing one in. The second tab is the same songs in folders, one for each artist,
 * made and kept by the app from who each song is by. The third holds the setlists, where +
 * makes a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    store: Store,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onLink: (List<String>) -> Unit,
    onSettings: () -> Unit,
    onSetlist: (String) -> Unit,
    onArtist: (String) -> Unit,
    // A sync with the Nextcloud to run when the list is pulled down from its top, returning the
    // state it left; null while the songs are kept only on this phone.
    onPull: (suspend () -> Cloud.State)? = null,
) {
    var pulled by remember { mutableStateOf<Pulled?>(null) }
    val pullScope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf<Job?>(null) }
    val pull: () -> Unit = {
        val run = onPull
        // A pull while the last one's answer is still up starts a fresh one.
        if (run != null && pulled != Pulled.SYNCING) {
            pulling?.cancel()
            pulling = pullScope.launch {
                pulled = Pulled.SYNCING
                pulled = pulledAfter(run())
                // The answer stays until the next press, not for a few seconds: on this panel a
                // line on a timer could be gone before it had been read.
                val at = Notice.presses
                snapshotFlow { Notice.presses }.first { it != at }
                pulled = null
            }
        }
    }
    val context = LocalContext.current
    val setlists = remember { Setlists.get(context) }
    val lists by setlists.all.collectAsStateWithLifecycle()
    val prefs = remember { com.wanderwildwood.utahon.data.Prefs.get(context) }
    var tab by rememberSaveable { mutableStateOf(prefs.tab) }
    var naming by remember { mutableStateOf(false) }
    val all by store.all.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    val shown = remember(all, query, searching) { if (searching) matching(all, query) else all }

    // Back from Artists or Setlists goes to Songs; only Back from Songs leaves the app. On this
    // panel the launcher would otherwise be under the next tap.
    androidx.activity.compose.BackHandler(enabled = tab != 0) {
        tab = 0
        prefs.tab = 0
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = {
                    if (tab == 0) {
                        BarButton(Icons.Search, stringResource(R.string.cd_search)) {
                            searching = !searching
                            if (!searching) query = ""
                        }
                    }
                    BarButton(Icons.Add, stringResource(if (tab == TAB_SETLISTS) R.string.setlist_new else R.string.cd_add)) {
                        if (tab == TAB_SETLISTS) naming = true else adding = true
                    }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StoneTabs(
                labels = listOf(stringResource(R.string.tab_songs), stringResource(R.string.tab_artists), stringResource(R.string.tab_setlists)),
                selected = tab,
                onSelect = {
                    tab = it
                    prefs.tab = it
                    searching = false
                    query = ""
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (tab == TAB_ARTISTS) {
                val artists = remember(all) { artistsOf(all) }
                if (artists.isEmpty()) {
                    TextMMD(
                        text = stringResource(R.string.list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                } else {
                    PagedColumn(modifier = Modifier.fillMaxSize(), onPullAtTop = if (onPull != null) pull else null, showCutRow = true) {
                        items(artists, key = { "a-" + it.first }) { (artist, n) ->
                            Column {
                                Column(Modifier.fillMaxWidth().clickable { onArtist(artist) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    TextMMD(
                                        text = artist.ifEmpty { stringResource(R.string.artist_none) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextMMD(text = pluralStringResource(R.plurals.song_count, n, n), style = MaterialTheme.typography.labelSmall)
                                }
                                DottedRule(start = 20.dp, end = 20.dp)
                            }
                        }
                    }
                }
                return@Column
            }
            if (tab == TAB_SETLISTS) {
                if (lists.isEmpty()) {
                    TextMMD(
                        text = stringResource(R.string.setlists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                } else {
                    PagedColumn(modifier = Modifier.fillMaxSize(), showCutRow = true) {
                        items(lists, key = { it.id }) { l ->
                            Column {
                                Column(Modifier.fillMaxWidth().clickable { onSetlist(l.id) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    TextMMD(text = l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val n = l.songs.count { id -> all.any { it.id == id } }
                                    TextMMD(text = pluralStringResource(R.plurals.song_count, n, n), style = MaterialTheme.typography.labelSmall)
                                }
                                DottedRule(start = 20.dp, end = 20.dp)
                            }
                        }
                    }
                }
                return@Column
            }
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextFieldMMD(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { TextMMD(text = stringResource(R.string.search_hint), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus).textActions(),
                )
            }
            pulled?.let { p ->
                TextMMD(
                    text = stringResource(
                        when (p) {
                            Pulled.SYNCING -> R.string.pull_syncing
                            Pulled.UP_TO_DATE -> R.string.pull_up_to_date
                            Pulled.UNREACHABLE -> R.string.pull_unreachable
                            Pulled.REFUSED -> R.string.pull_refused
                            Pulled.FAILED -> R.string.pull_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            when {
                all.isEmpty() -> TextMMD(
                    text = stringResource(R.string.list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                shown.isEmpty() -> TextMMD(
                    text = stringResource(R.string.search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                else -> PagedColumn(modifier = Modifier.fillMaxSize(), onPullAtTop = if (onPull != null) pull else null, showCutRow = true) {
                    items(shown, key = { it.id }) { s ->
                        Column {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(s.id) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                TextMMD(
                                    text = s.title.ifBlank { stringResource(R.string.untitled) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (s.artist.isNotBlank()) {
                                    TextMMD(text = s.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            DottedRule(start = 20.dp, end = 20.dp)
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        EInkDialog(onDismiss = { adding = false }) {
            TextMMD(text = stringResource(R.string.new_song), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            WideButton(stringResource(R.string.new_from_link)) {
                adding = false
                askLink = true
            }
            WideButton(stringResource(R.string.new_type_in)) {
                adding = false
                onNew()
            }
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.new_share_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        EInkDialog(onDismiss = { naming = false }) {
            TextMMD(text = stringResource(R.string.setlist_new), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // The name is what this is for: the field takes the focus, asked from inside the dialog a
            // frame after it is drawn, and the keyboard comes up.
            val nameField = remember { FocusRequester() }
            val keyboardNow = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { nameField.requestFocus() }
                keyboardNow?.show()
            }
            Spacer(Modifier.height(8.dp))
            Field(stringResource(R.string.setlist_name), name, { name = it }, modifier = Modifier.focusRequester(nameField))
            Spacer(Modifier.height(8.dp))
            DialogButtons(
                cancel = stringResource(R.string.cancel),
                confirm = stringResource(R.string.setlist_make),
                onCancel = { naming = false },
                onConfirm = {
                    naming = false
                    onSetlist(setlists.add(name).id)
                },
                confirmOn = true,
                confirmEnabled = name.isNotBlank(),
            )
        }
    }
    if (askLink) LinkDialog(onDismiss = { askLink = false }, onRead = {
        askLink = false
        onLink(it)
    })
}

/** The address of a song's page, typed or pasted; or several, each read in its turn. */
@Composable
private fun LinkDialog(onDismiss: () -> Unit, onRead: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.link_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        // The field is what the dialog is for: it takes the focus as it opens and the keyboard
        // comes up. Asked from inside the dialog, a frame after it is drawn: the field lives in
        // the dialog's own window, which a request from the screen beneath does not reach.
        val keyboardNow = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboardNow?.show()
        }
        Spacer(Modifier.height(8.dp))
        TextFieldMMD(
            value = text,
            onValueChange = {
                text = it
                wrong = false
            },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            placeholder = { TextMMD(text = "https://", style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).textActions(),
        )
        TextMMD(text = stringResource(R.string.link_several), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        if (wrong) TextMMD(text = stringResource(R.string.link_wrong), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        WideButton(stringResource(R.string.link_read), filled = true) {
            val t = text.trim()
            val urls = Reader.addressesIn(t).ifEmpty { listOfNotNull(if (t.contains('.') && t.none { it.isWhitespace() }) "https://$t" else null) }
            if (urls.isEmpty()) wrong = true else onRead(urls)
        }
        WideButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

/** A list of pages being read one after another: each address, and what became of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val items by com.wanderwildwood.utahon.importer.Batch.items.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.batch_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), showCutRow = true) {
            items(items, key = { it.url }) { item ->
                val state = com.wanderwildwood.utahon.importer.Batch.State.entries
                Column {
                    Column(
                        Modifier.fillMaxWidth()
                            .let { m -> item.saved?.let { id -> m.clickable { onOpen(id) } } ?: m }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        TextMMD(
                            text = item.title.ifBlank { item.url.substringAfterLast('/').ifBlank { item.url } },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextMMD(
                            text = when (item.state) {
                                state[0] -> stringResource(R.string.batch_waiting)
                                state[1] -> stringResource(R.string.batch_reading)
                                state[2] -> stringResource(R.string.batch_saved)
                                state[3] -> stringResource(R.string.already_saved)
                                state[4] -> stringResource(R.string.batch_no_song)
                                else -> if (item.code == 0) stringResource(R.string.batch_unreachable) else stringResource(R.string.batch_failed, item.code)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    DottedRule(start = 20.dp, end = 20.dp)
                }
            }
        }
    }
}

/** The page being read, or why it could not be. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingPage(url: String, failed: Int?, onRetry: () -> Unit, onTypeIn: () -> Unit, onBack: () -> Unit) {
    val host = remember(url) { runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.reading_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (failed == null) {
                TextMMD(text = stringResource(R.string.reading, host), style = MaterialTheme.typography.bodyMedium)
            } else if (Reader.refused(failed)) {
                TextMMD(text = stringResource(R.string.read_refused, failed), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                HouseButton(stringResource(R.string.type_it_in), onTypeIn, Modifier.fillMaxWidth(), on = true)
                Spacer(Modifier.height(8.dp))
                HouseButton(stringResource(R.string.cancel), onBack, Modifier.fillMaxWidth())
            } else {
                TextMMD(
                    text = when (failed) {
                        Reader.NEEDS_HTTPS -> stringResource(R.string.read_needs_https, host)
                        0 -> stringResource(R.string.read_failed_offline, host)
                        else -> stringResource(R.string.read_failed_code, host, failed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                WideButton(stringResource(R.string.try_again), filled = true, onClick = onRetry)
                WideButton(stringResource(R.string.type_it_in), onClick = onTypeIn)
            }
        }
    }
}

private const val TAB_ARTISTS = 1
private const val TAB_SETLISTS = 2

private val byName = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }

/** The same artist however it was typed: "Bright Eyes", "bright eyes " and "BRIGHT EYES" are one folder. */
internal fun sameArtist(a: String, b: String): Boolean = byName.compare(a.trim(), b.trim()) == 0

/**
 * The artists the songs are by, in order of name, each with how many of its songs there are.
 * Songs with no artist come last, under an empty name. An artist goes by the spelling of its
 * first song.
 */
internal fun artistsOf(all: List<Song>): List<Pair<String, Int>> {
    val out = ArrayList<Pair<String, Int>>()
    for (s in all.sortedWith { a, b -> byName.compare(a.artist.trim(), b.artist.trim()) }) {
        val name = s.artist.trim()
        val last = out.lastOrNull()
        if (last != null && sameArtist(last.first, name)) out[out.lastIndex] = last.first to last.second + 1 else out += name to 1
    }
    return out.filter { it.first.isNotEmpty() } + out.filter { it.first.isEmpty() }
}

/** One artist's songs, by title: the inside of a folder on the Artists tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(store: Store, artist: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val all by store.all.collectAsStateWithLifecycle()
    val songs = remember(all, artist) { all.filter { sameArtist(it.artist, artist) } }
    if (songs.isEmpty()) {
        // The last of them deleted, or given to someone else.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = artist.ifEmpty { stringResource(R.string.artist_none) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), showCutRow = true) {
            items(songs, key = { it.id }) { s ->
                Column {
                    TextMMD(
                        text = s.title.ifBlank { stringResource(R.string.untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                    DottedRule(start = 20.dp, end = 20.dp)
                }
            }
        }
    }
}

/** Songs whose title, artist or words hold every word of [query], accents and case aside. */
internal fun matching(all: List<Song>, query: String): List<Song> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return all
    return all.filter { s ->
        val hay = fold(s.title + " " + s.artist + " " + s.text)
        words.all { hay.contains(it) }
    }
}

private val MARKS = Regex("\\p{Mn}+")

private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
