package com.wanderwildwood.utahon.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Place
import com.wanderwildwood.utahon.data.Prefs
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.data.Store
import com.wanderwildwood.utahon.music.Chord
import com.wanderwildwood.utahon.music.ChordPro
import com.wanderwildwood.utahon.music.Instrument
import com.wanderwildwood.utahon.music.Mark
import com.wanderwildwood.utahon.music.Sheet
import com.wanderwildwood.utahon.music.SheetLine
import com.wanderwildwood.utahon.music.Voicings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The auto scroll's speeds, 1 to 9, as the seconds it waits between half-page steps. The reader
 * sees only the number: a speed is felt, not counted.
 */
internal val SPEED_SECONDS = listOf(60, 45, 35, 27, 21, 16, 12, 9, 6)
const val SPEED_USUAL = 5

/** The speed whose wait is nearest to [seconds]. */
internal fun speedFor(seconds: Int): Int = SPEED_SECONDS.indices.minBy { kotlin.math.abs(SPEED_SECONDS[it] - seconds) } + 1

/** How much of the screen's width the scrollbar and the sheet's own margins take. */
private val SHEET_MARGINS = 40.dp + 20.dp + 4.dp

/**
 * One song, read top to bottom: what it is, the key it is set in (and a way to move it), then
 * the sheet. A chord pressed shows where the fingers go. The bar at the foot starts the auto
 * scroll, which moves the sheet on half a page at a time, and lists the song's chords. A pedal's
 * keys move the sheet the same half page, and the sheet turns sideways with the phone. Opened
 * from a setlist ([place]), the sheet ends with the way on to the next song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(store: Store, id: String, place: Place?, onBack: () -> Unit, onEdit: () -> Unit, onNext: (String, Place) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val all by store.all.collectAsStateWithLifecycle()
    val song = all.firstOrNull { it.id == id }
    if (song == null) {
        // Deleted, or never there: nothing to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val chosen by prefs.instrument.collectAsStateWithLifecycle()
    val instrument = Instrument.forKind(song.kind, chosen)
    val size by prefs.size.collectAsStateWithLifecycle()
    val stayOn by prefs.stayOn.collectAsStateWithLifecycle()
    val turn by prefs.turn.collectAsStateWithLifecycle()
    val setlists = remember { Setlists.get(context) }
    val lists by setlists.all.collectAsStateWithLifecycle()
    // The song after this one in the setlist it was opened from, songs since deleted passed over.
    val next = remember(place, lists, all) {
        val list = place?.let { p -> lists.firstOrNull { it.id == p.setlist } } ?: return@remember null
        ((place.at + 1)..list.songs.lastIndex).firstNotNullOfOrNull { i ->
            all.firstOrNull { it.id == list.songs[i] }?.let { it to Place(list.id, i) }
        }
    }
    val tuner = remember { Elsewhere.hasTuner(context) }
    val scope = rememberCoroutineScope()

    val lines = remember(song.text) { Sheet.read(song.text) }
    val flats = remember(lines, song.transpose) { Sheet.flats(lines, song.transpose) }
    val chords = remember(lines, song.transpose, flats) { Sheet.chords(lines).map { it.transposed(song.transpose, flats) }.distinctBy { it.name } }
    fun shown(c: Chord) = c.transposed(song.transpose, flats)

    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Whether the sheet's list shows the row cut at its foot (see the list below).
    val cut by androidx.compose.runtime.rememberUpdatedState(sideways)
    var chord by remember { mutableStateOf<Chord?>(null) }
    var listing by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var running by remember(id) { mutableStateOf(false) }
    // The speed worked out from the song's length when the scroll was started, until − or + sets one.
    var worked by remember(id) { mutableStateOf(0) }
    val speed = (song.speed.takeIf { it > 0 } ?: worked.takeIf { it > 0 } ?: SPEED_USUAL).coerceIn(1, SPEED_SECONDS.size)
    val pace = SPEED_SECONDS[speed - 1]
    val state = remember(id) { LazyListState() }

    DisposableEffect(state) {
        Hands.onTurn = { forward -> scope.launch { if (forward) state.stepOn(tailOf(cut)) else state.stepBack(tailOf(cut)) } }
        onDispose { Hands.onTurn = null }
    }
    DisposableEffect(turn) {
        Hands.turning?.invoke(turn)
        onDispose { Hands.turning?.invoke(false) }
    }

    // The sheet is read with both hands on an instrument, so the screen stays lit while it is
    // open (a setting), and always while the auto scroll runs. On the activity's window rather
    // than on the view: the view's flag was seen to outlive the screen that set it.
    val window = LocalActivity.current?.window
    val lit = stayOn || running
    DisposableEffect(window, lit) {
        if (lit) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(running, pace) {
        while (running) {
            delay(pace * 1000L)
            if (!state.stepOn(tailOf(cut))) running = false
        }
    }

    val body = when (size) {
        0 -> MaterialTheme.typography.bodySmall
        1 -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    // Tablature only reads when every character is as wide as the next.
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // Sideways the panel is short, and it is turned that way to read: the bar with Back,
            // the pencil and More gives its room to the sheet, and is there again upright.
            if (!sideways) {
                TopAppBarMMD(
                    title = {},
                    navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                    actions = {
                        BarButton(Icons.Edit, stringResource(R.string.cd_edit), onEdit)
                        BarButton(Icons.More, stringResource(R.string.cd_more)) { more = true }
                    },
                )
            }
        },
        bottomBar = {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (running) {
                        HouseButton("−", { store.set(id) { it.copy(speed = (speed - 1).coerceAtLeast(1)) } }, compact = true)
                        TextMMD(
                            text = stringResource(R.string.scroll_speed, speed),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        HouseButton("+", { store.set(id) { it.copy(speed = (speed + 1).coerceAtMost(SPEED_SECONDS.size)) } }, compact = true)
                        HouseButton(stringResource(R.string.scroll_stop), { running = false }, on = true)
                    } else {
                        HouseButton(stringResource(R.string.scroll_start), {
                            // A song whose length is known is spread over it: so many steps, so many seconds each.
                            if (song.speed == 0 && song.length > 0) worked = speedFor(song.length / (state.stepsLeft(tailOf(cut)) + 1))
                            running = true
                        }, Modifier.weight(1f))
                        if (chords.isNotEmpty()) HouseButton(stringResource(R.string.chords_button), { listing = true }, Modifier.weight(1f))
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val measurer = rememberTextMeasurer()
            val columns = with(LocalDensity.current) {
                val one = measurer.measure("0", mono).size.width.coerceAtLeast(1)
                ((maxWidth - SHEET_MARGINS).toPx() / one).toInt().coerceAtLeast(12)
            }
            PagedColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 4.dp),
                // Sideways the page is short, and a system of tablature that does not fit whole would
                // leave most of it blank: there the row the foot cuts is drawn, and the next page
                // opens on it whole.
                showCutRow = sideways,
            ) {
                if (sideways) {
                    // Sideways the page is short and is turned that way to play from: the song says
                    // who and what it is in one line, and the rest of its head waits upright.
                    item(key = "head") {
                        TextMMD(
                            text = Words.heading(song).ifBlank { stringResource(R.string.untitled) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                        )
                    }
                } else {
                item(key = "title") {
                    TextMMD(
                        text = song.title.ifBlank { stringResource(R.string.untitled) },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (song.artist.isNotBlank()) {
                    item(key = "artist") { TextMMD(text = song.artist, style = MaterialTheme.typography.bodyMedium) }
                }
                val about = Words.about(context, song)
                if (about.isNotEmpty()) {
                    item(key = "about") {
                        // A sheet in a tuning of its own: the natural next move is the tuner.
                        val toTuner = tuner && song.tuning.isNotBlank() && Words.showsTuning(song)
                        TextMMD(
                            text = about,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp).let { if (toTuner) it.clickable { Elsewhere.tuner(context) } else it },
                        )
                    }
                }
                if (chords.isNotEmpty()) {
                    item(key = "key") {
                        Mover(
                            label = Words.moved(context, song.transpose),
                            onDown = { store.set(id) { it.copy(transpose = wrap(it.transpose - 1)) } },
                            onUp = { store.set(id) { it.copy(transpose = wrap(it.transpose + 1)) } },
                        )
                    }
                }
                }
                var n = 0
                for (line in lines) {
                    val key = "l${n++}"
                    when (line) {
                        SheetLine.Blank -> item(key = key) { Spacer(Modifier.height(12.dp)) }
                        is SheetLine.Part -> item(key = key) {
                            TextMMD(text = line.name, style = body, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        }
                        is SheetLine.Words -> item(key = key) { TextMMD(text = line.text, style = body) }
                        is SheetLine.Chords -> item(key = key) { ChordRow(line, body, ::shown) { chord = it } }
                        is SheetLine.Said -> item(key = key) { SaidRow(line, body, ::shown) { chord = it } }
                        is SheetLine.Sung -> item(key = key) { SungRow(line, body, ::shown) { chord = it } }
                        is SheetLine.Tab -> Sheet.cut(line.rows, columns).forEachIndexed { i, system ->
                            item(key = "$key-$i") {
                                TextMMD(text = system.joinToString("\n"), style = mono, softWrap = false, modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
                next?.let { (after, where) ->
                    item(key = "next") {
                        WideButton(stringResource(R.string.setlist_next, after.title.ifBlank { stringResource(R.string.untitled) }), modifier = Modifier.padding(top = 12.dp, end = 8.dp)) {
                            onNext(after.id, where)
                        }
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    chord?.let { c -> ChordDialog(c, instrument, onDismiss = { chord = null }) }
    if (listing) {
        ChordsDialog(chords, instrument, onDismiss = { listing = false }, onPick = {
            listing = false
            chord = it
        })
    }
    if (more) {
        MoreDialog(store, setlists, song, lines, tuner, onDismiss = { more = false }, onDeleted = {
            more = false
            onBack()
        })
    }
}

/** How many items at the list's end are not rows: its blank tail, which a list that shows the cut row does not have. */
private fun tailOf(cut: Boolean): Int = if (cut) 0 else 1

/** Twelve semitones up is the same chords again, so the count runs from eleven down to eleven up. */
private fun wrap(semitones: Int): Int = if (semitones > 11 || semitones < -11) 0 else semitones

/**
 * Moves the sheet on by half a page: the row halfway down comes to the top, so the lines being
 * played stay in view while the next ones arrive. False when the last row is already shown.
 */
private suspend fun LazyListState.stepOn(tail: Int): Boolean {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    // Upright, the list's last item is its blank tail, not a row.
    val last = info.totalItemsCount - 1 - tail
    val rows = info.visibleItemsInfo.filter { it.index <= last }
    if (rows.isEmpty()) return false
    val whole = rows.filter { it.offset >= 0 && it.offset + it.size <= height }
    if (whole.any { it.index == last }) return false
    val first = rows.first().index
    val target = (whole.getOrNull(whole.size / 2)?.index ?: (first + 1)).coerceAtLeast(first + 1).coerceAtMost(last)
    scrollToItem(target)
    return true
}

/** Moves the sheet back by about as much as [stepOn] moves it on. False at the top. */
private suspend fun LazyListState.stepBack(tail: Int): Boolean {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val rows = info.visibleItemsInfo.filter { it.index <= info.totalItemsCount - 1 - tail }
    val first = rows.firstOrNull()?.index ?: return false
    if (first == 0) return false
    val whole = rows.count { it.offset >= 0 && it.offset + it.size <= height }
    scrollToItem((first - (whole / 2).coerceAtLeast(1)).coerceAtLeast(0))
    return true
}

/** About how many steps of [stepOn] are left before the last row is shown. */
private fun LazyListState.stepsLeft(tail: Int): Int {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val last = info.totalItemsCount - 1 - tail
    val whole = info.visibleItemsInfo.filter { it.index <= last && it.offset >= 0 && it.offset + it.size <= height }
    val shown = whole.lastOrNull()?.index ?: return 0
    val each = (whole.size / 2).coerceAtLeast(1)
    return ((last - shown) + each - 1) / each
}

/** The key the sheet is shown in, with a − and a + either side to move it a semitone. */
@Composable
private fun Mover(label: String, onDown: () -> Unit, onUp: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp, end = 8.dp),
    ) {
        OutlinedButtonMMD(onClick = onDown, modifier = Modifier.size(48.dp)) {
            TextMMD(text = "−", style = MaterialTheme.typography.titleMedium)
        }
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        OutlinedButtonMMD(onClick = onUp, modifier = Modifier.size(48.dp)) {
            TextMMD(text = "+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A line of chords with no words under it. Each chord can be pressed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChordRow(line: SheetLine.Chords, style: TextStyle, shown: (Chord) -> Chord, onChord: (Chord) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        for (mark in line.marks) {
            when (mark) {
                is Mark.Of -> {
                    val c = shown(mark.chord)
                    TextMMD(text = c.name, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onChord(c) })
                }
                is Mark.Word -> TextMMD(text = mark.text, style = style)
            }
        }
    }
}

/** A sentence with chords named in it: the words as words, each chord bold and pressable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaidRow(line: SheetLine.Said, style: TextStyle, shown: (Chord) -> Chord, onChord: (Chord) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (mark in line.marks) {
            when (mark) {
                is Mark.Word -> TextMMD(text = mark.text, style = style)
                is Mark.Of -> {
                    val c = shown(mark.chord)
                    Row(Modifier.clickable { onChord(c) }) {
                        if (mark.before.isNotEmpty()) TextMMD(text = mark.before, style = style)
                        TextMMD(text = c.name, style = style, fontWeight = FontWeight.Bold)
                        if (mark.after.isNotEmpty()) TextMMD(text = mark.after, style = style)
                    }
                }
            }
        }
    }
}

/**
 * A line of words with its chords over it. The words wrap as a sentence does; each chord stays
 * over the syllable it was written over, and pressing the chord or its syllable shows the chord.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SungRow(line: SheetLine.Sung, style: TextStyle, shown: (Chord) -> Chord, onChord: (Chord) -> Unit) {
    val words = remember(line) { Sheet.words(line) }
    WordFlow(gap = 5.dp, modifier = Modifier.padding(vertical = 2.dp)) {
        for (word in words) {
            Row(verticalAlignment = Alignment.Bottom) {
                for (piece in word) {
                    val c = piece.chord?.let(shown)
                    if (c == null) {
                        // No chord over it: only the words, so a wrapped line with no chord on it
                        // sits close under the line before, with no empty row above it.
                        TextMMD(text = piece.text, style = style)
                    } else {
                        Column(Modifier.clickable { onChord(c) }) {
                            // Room after a chord, so the next one never runs into it.
                            TextMMD(text = c.name, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                            TextMMD(text = piece.text, style = style)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Words set one after another and wrapped like a sentence, each line as tall as its tallest
 * word and the words standing on its foot. Unlike a flow of equal rows, a line whose words carry
 * no chord is only as tall as the words.
 */
@Composable
private fun WordFlow(gap: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val space = gap.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        // Each line: where it starts down the row, how tall it is, and its words with their x.
        class Line(var height: Int = 0, val words: MutableList<Pair<androidx.compose.ui.layout.Placeable, Int>> = mutableListOf())
        val lines = mutableListOf(Line())
        var x = 0
        for (p in placeables) {
            if (x > 0 && x + p.width > constraints.maxWidth) {
                lines += Line()
                x = 0
            }
            val line = lines.last()
            line.words += p to x
            line.height = maxOf(line.height, p.height)
            x += p.width + space
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOf { l -> l.words.lastOrNull()?.let { it.second + it.first.width } ?: 0 }
        layout(width, lines.sumOf { it.height }.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            for (line in lines) {
                for ((p, at) in line.words) p.place(at, y + line.height - p.height)
                y += line.height
            }
        }
    }
}

/** Where the fingers go for one chord, and another way to play it where there is one. */
@Composable
private fun ChordDialog(chord: Chord, instrument: Instrument, onDismiss: () -> Unit) {
    val ways = remember(chord, instrument) { Voicings.of(chord, instrument) }
    var at by remember(chord, instrument) { mutableStateOf(0) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = chord.name, style = MaterialTheme.typography.titleLarge)
        TextMMD(text = stringResource(instrumentName(instrument)), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        val way = ways.getOrNull(at)
        if (way == null) {
            TextMMD(text = stringResource(R.string.chord_no_shape), style = MaterialTheme.typography.bodyMedium)
        } else {
            ChordBox(way, way.toString(), Modifier.align(Alignment.CenterHorizontally), width = 170.dp)
            TextMMD(
                text = way.toString(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (ways.size > 1) {
            HouseButton(stringResource(R.string.chord_another, at + 1, ways.size), { at = (at + 1) % ways.size }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        HouseButton(stringResource(R.string.about_close), onDismiss, Modifier.fillMaxWidth())
    }
}

/** Every chord the song uses, two boxes to a row; a press opens the one. */
@Composable
private fun ChordsDialog(chords: List<Chord>, instrument: Instrument, onDismiss: () -> Unit, onPick: (Chord) -> Unit) {
    val rows = remember(chords) { chords.chunked(2) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.chords_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        TextMMD(text = stringResource(instrumentName(instrument)), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        PagedColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            rows.forEachIndexed { i, pair ->
                item(key = "r$i") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        for (c in pair) {
                            val way = remember(c, instrument) { Voicings.of(c, instrument, limit = 1).firstOrNull() }
                            Column(
                                Modifier.weight(1f).clickable { onPick(c) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                TextMMD(text = c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (way != null) ChordBox(way, way.toString(), width = 104.dp)
                                else Spacer(Modifier.height(104.dp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HouseButton(stringResource(R.string.about_close), onDismiss, Modifier.fillMaxWidth())
    }
}

fun instrumentName(instrument: Instrument): Int = when (instrument) {
    Instrument.GUITAR -> R.string.instrument_guitar
    Instrument.UKULELE -> R.string.instrument_ukulele
    Instrument.MANDOLIN -> R.string.instrument_mandolin
}

/**
 * What else can be done with a song: send it as words or keep it as a ChordPro file (both in
 * the key it is shown in), put it in a setlist, open the page it came from or Tuner, or delete
 * it. The delete asks in its own face.
 */
@Composable
private fun MoreDialog(store: Store, setlists: Setlists, song: Song, lines: List<SheetLine>, tuner: Boolean, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(FilesCreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(ChordPro.write(song.title, song.artist, song.capo, song.key, lines, song.transpose).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            Elsewhere.say(context, if (ok) R.string.saved_to_file else R.string.save_failed)
            onDismiss()
        }
    }
    if (picking) {
        SetlistPicker(setlists, song.id, onDismiss = onDismiss)
        return
    }
    EInkDialog(onDismiss = onDismiss) {
        // The dialog is its own window, so its armed row needs its own scope to see a tap elsewhere.
        ArmedScope {
            Column {
                var arming by rememberArmedFlag()
                TextMMD(text = song.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                WideButton(stringResource(R.string.share_text)) {
                    Elsewhere.share(context, Words.heading(song), Words.heading(song) + "\n\n" + Sheet.plain(lines, song.transpose))
                    onDismiss()
                }
                WideButton(stringResource(R.string.save_file)) { save.launch(fileName(song.title) + ".cho") }
                WideButton(stringResource(R.string.setlist_add_to)) { picking = true }
                if (song.url.isNotBlank()) {
                    WideButton(stringResource(R.string.open_page)) {
                        Elsewhere.page(context, song.url)
                        onDismiss()
                    }
                }
                if (tuner) {
                    WideButton(stringResource(R.string.open_tuner)) {
                        Elsewhere.tuner(context)
                        onDismiss()
                    }
                }
                WideButton(stringResource(if (arming) R.string.delete_armed else R.string.delete)) {
                    if (arming) {
                        store.delete(song.id)
                        onDeleted()
                    } else {
                        arming = true
                    }
                }
            }
        }
    }
}

/** Which setlist a song goes into: one of those there are, or a new one named here. */
@Composable
fun SetlistPicker(setlists: Setlists, songId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lists by setlists.all.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(lists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    fun put(id: String) {
        setlists.change(id) { if (songId in it.songs) it else it.copy(songs = it.songs + songId) }
        Elsewhere.say(context, R.string.setlist_added)
        onDismiss()
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.setlist_add_to), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (naming) {
            Field(stringResource(R.string.setlist_name), name, { name = it })
            Spacer(Modifier.height(8.dp))
            DialogButtons(
                cancel = stringResource(R.string.cancel),
                confirm = stringResource(R.string.setlist_make),
                onCancel = { if (lists.isEmpty()) onDismiss() else naming = false },
                onConfirm = { put(setlists.add(name).id) },
                confirmOn = true,
                confirmEnabled = name.isNotBlank(),
            )
        } else {
            PagedColumn(modifier = Modifier.heightIn(max = 330.dp)) {
                lists.forEach { l ->
                    item(key = l.id) { WideButton(l.name) { put(l.id) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            DialogButtons(
                cancel = stringResource(R.string.cancel),
                confirm = stringResource(R.string.setlist_new),
                onCancel = onDismiss,
                onConfirm = { naming = true },
            )
        }
    }
}

/** A song's title as a file name: letters, digits and dashes. */
fun fileName(name: String): String =
    name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "-").trim('-').take(60).ifEmpty { "song" }
