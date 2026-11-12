package com.wanderwildwood.utahon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.data.Store
import com.wanderwildwood.utahon.data.lengthFrom
import com.wanderwildwood.utahon.data.lengthText
import com.wanderwildwood.utahon.music.ChordPro

/**
 * A song typed in, pasted in or changed. The sheet is one plain box, set in a typewriter face
 * so a chord typed over a word stays over it: chords go on a line of their own above the words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(store: Store, id: String?, draft: Song?, note: Int, onDone: (String?) -> Unit) {
    val start = remember(id) { id?.let { store.get(it) } ?: draft ?: Song(id = "", title = "") }
    var title by rememberSaveable { mutableStateOf(start.title) }
    var artist by rememberSaveable { mutableStateOf(start.artist) }
    var capo by rememberSaveable { mutableStateOf(if (start.capo > 0) start.capo.toString() else "") }
    var tuning by rememberSaveable { mutableStateOf(start.tuning) }
    var text by rememberSaveable { mutableStateOf(start.text) }
    var url by rememberSaveable { mutableStateOf(start.url) }
    var length by rememberSaveable { mutableStateOf(lengthText(start.length)) }
    var leaving by rememberArmedFlag(disarmOnBack = false)
    var nameless by remember { mutableStateOf(false) }

    // A draft made from a page or from shared text is not yet saved, so leaving it asks.
    fun changed() = (id == null && draft != null) || title != start.title || artist != start.artist ||
        capo != (if (start.capo > 0) start.capo.toString() else "") || tuning != start.tuning ||
        text != start.text || url != start.url || length != lengthText(start.length)

    fun save() {
        if (title.isBlank()) {
            nameless = true
            return
        }
        // A sheet pasted in as ChordPro (chords in brackets inside the words) is kept the way
        // every other sheet is: chords over the words.
        val pro = if (ChordPro.looksLike(text)) ChordPro.read(text) else null
        val kept = store.save(
            start.copy(
                title = title.trim(),
                artist = artist.trim().ifEmpty { pro?.artist.orEmpty() },
                capo = capo.trim().toIntOrNull()?.coerceIn(0, 24) ?: pro?.capo ?: 0,
                tuning = tuning.trim(),
                text = pro?.text ?: (text.replace("\r\n", "\n").trimEnd() + "\n"),
                url = url.trim(),
                length = lengthFrom(length),
            ),
        )
        onDone(kept.id)
    }

    fun leave() {
        if (!changed() || leaving) onDone(null) else leaving = true
    }

    androidx.activity.compose.BackHandler { leave() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = when {
                            leaving -> stringResource(R.string.discard_armed)
                            id == null -> stringResource(R.string.new_song)
                            else -> stringResource(R.string.edit_song)
                        },
                    )
                },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_discard)) { leave() } },
                actions = { BarText(stringResource(R.string.save)) { save() } },
            )
        },
    ) { padding ->
        PagedColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            showCutRow = true,
        ) {
            if (note != 0) {
                item(key = "note") {
                    TextMMD(text = stringResource(note), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = "title") {
                Field(stringResource(R.string.field_title), title, {
                    title = it
                    nameless = false
                })
                if (nameless) TextMMD(text = stringResource(R.string.field_title_needed), style = MaterialTheme.typography.labelSmall)
            }
            item(key = "artist") { Field(stringResource(R.string.field_artist), artist, { artist = it }) }
            item(key = "text") {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    TextMMD(text = stringResource(R.string.field_text), style = MaterialTheme.typography.labelSmall)
                    // A sheet's lines are not wrapped: a chord typed over a word has to stay over
                    // it, and a line broken in two puts it over the wrong one. The box is as wide as
                    // its longest line and is moved sideways by hand to reach the end of one. (The
                    // one place something here slides: there is no page to turn within a line.)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val room = maxWidth
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            TextFieldMMD(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = false,
                                minLines = 8,
                                maxLines = 14,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                modifier = Modifier.widthIn(min = room).width(IntrinsicSize.Max).textActions(),
                            )
                        }
                    }
                    TextMMD(text = stringResource(R.string.field_text_note), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item(key = "capo") { Field(stringResource(R.string.field_capo), capo, { capo = it.filter(Char::isDigit).take(2) }, number = true) }
            item(key = "tuning") { Field(stringResource(R.string.field_tuning), tuning, { tuning = it }, hint = stringResource(R.string.field_tuning_hint), words = false) }
            item(key = "length") {
                Field(stringResource(R.string.field_length), length, { length = it.filter { c -> c.isDigit() || c == ':' }.take(5) }, hint = stringResource(R.string.field_length_hint), words = false)
                TextMMD(text = stringResource(R.string.field_length_note), style = MaterialTheme.typography.labelSmall)
            }
            item(key = "url") { Field(stringResource(R.string.field_url), url, { url = it }, url = true) }
            item(key = "foot") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
