package com.wanderwildwood.utahon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Place
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Store

/**
 * One setlist: its songs in the order they are played. A song pressed opens with the way on to
 * the next at its foot. The pencil turns the rows into ones that can be moved up, moved down
 * or taken out, and shows the rows for adding songs, renaming and deleting the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistScreen(store: Store, id: String, onBack: () -> Unit, onOpen: (String, Place) -> Unit) {
    val context = LocalContext.current
    val setlists = remember { Setlists.get(context) }
    val lists by setlists.all.collectAsStateWithLifecycle()
    val all by store.all.collectAsStateWithLifecycle()
    val list = lists.firstOrNull { it.id == id }
    if (list == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // A new list opens ready to be filled.
    var editing by rememberSaveable { mutableStateOf(list.songs.isEmpty()) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // Each song with its place in the list; one deleted from the book since is passed over.
    val rows = list.songs.mapIndexedNotNull { i, songId -> all.firstOrNull { it.id == songId }?.let { i to it } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    if (editing) BarText(stringResource(R.string.setlist_done)) { editing = false }
                    else BarButton(Icons.Edit, stringResource(R.string.cd_edit)) { editing = true }
                },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), showCutRow = true) {
            if (rows.isEmpty() && !editing) {
                item(key = "empty") {
                    TextMMD(
                        text = stringResource(R.string.setlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            rows.forEachIndexed { n, (at, song) ->
                item(key = "s$at-${song.id}") {
                    Column {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .let { if (editing) it else it.clickable { onOpen(song.id, Place(list.id, at)) } }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            TextMMD(
                                text = "${n + 1}. " + song.title.ifBlank { stringResource(R.string.untitled) },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (song.artist.isNotBlank()) {
                                TextMMD(text = song.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (editing) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val before = rows.getOrNull(n - 1)?.first
                                    val after = rows.getOrNull(n + 1)?.first
                                    HouseButton(stringResource(R.string.setlist_up), { before?.let { to -> setlists.change(id) { l -> l.copy(songs = Setlists.moved(l.songs, at, to)) } } }, Modifier.weight(1f), enabled = before != null, compact = true)
                                    HouseButton(stringResource(R.string.setlist_down), { after?.let { to -> setlists.change(id) { l -> l.copy(songs = Setlists.moved(l.songs, at, to)) } } }, Modifier.weight(1f), enabled = after != null, compact = true)
                                    HouseButton(stringResource(R.string.setlist_take_out), { setlists.change(id) { l -> l.copy(songs = l.songs.filterIndexed { i, _ -> i != at }) } }, Modifier.weight(1f), compact = true)
                                }
                            }
                        }
                        DottedRule(start = 20.dp, end = 20.dp)
                    }
                }
            }
            if (editing) {
                item(key = "add") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(8.dp))
                        WideButton(stringResource(R.string.setlist_add_songs), filled = true) { adding = true }
                        WideButton(stringResource(R.string.setlist_rename)) { renaming = true }
                    }
                }
                item(key = "delete") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        ArmedRow(stringResource(R.string.setlist_delete), stringResource(R.string.setlist_delete_armed)) {
                            setlists.delete(id)
                            onBack()
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        EInkDialog(onDismiss = { adding = false }) {
            TextMMD(text = stringResource(R.string.setlist_add_songs), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            if (all.isEmpty()) {
                TextMMD(text = stringResource(R.string.setlist_no_songs), style = MaterialTheme.typography.bodyMedium)
            } else {
                PagedColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    all.forEach { song ->
                        item(key = song.id) {
                            CheckRow(
                                Words.heading(song).ifBlank { stringResource(R.string.untitled) },
                                song.id in list.songs,
                                { on ->
                                    setlists.change(id) { l -> if (on) l.copy(songs = l.songs + song.id) else l.copy(songs = l.songs.filterNot { it == song.id }) }
                                },
                                Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HouseButton(stringResource(R.string.setlist_done), { adding = false }, Modifier.fillMaxWidth(), on = true)
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(list.name) }
        EInkDialog(onDismiss = { renaming = false }) {
            TextMMD(text = stringResource(R.string.setlist_rename), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Field(stringResource(R.string.setlist_name), name, { name = it })
            Spacer(Modifier.height(8.dp))
            DialogButtons(
                cancel = stringResource(R.string.cancel),
                confirm = stringResource(R.string.save),
                onCancel = { renaming = false },
                onConfirm = {
                    setlists.change(id) { it.copy(name = name.trim()) }
                    renaming = false
                },
                confirmOn = true,
                confirmEnabled = name.isNotBlank(),
            )
        }
    }
}
