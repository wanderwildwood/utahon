package com.wanderwildwood.utahon.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.wanderwildwood.utahon.music.Tablature
import com.wanderwildwood.utahon.music.Voicings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The auto scroll's speeds, 1 to 9, as the seconds it takes over a third of a page. It moves a
 * line at a time ([lineWait]), so the third of a page is its pace and not its step. The reader
 * sees only the number: a speed is felt, not counted. Even the slowest covers two thirds of a
 * page a minute: a sheet that sat still for a whole minute at speed 1 looked like one that was
 * not scrolling at all.
 */
internal val SPEED_SECONDS = listOf(30, 24, 19, 15, 12, 9, 7, 5, 3)
const val SPEED_USUAL = 5

/** The speed whose wait is nearest to [seconds]. */
internal fun speedFor(seconds: Int): Int = SPEED_SECONDS.indices.minBy { kotlin.math.abs(SPEED_SECONDS[it] - seconds) } + 1

/** The shortest the auto scroll leaves a line at the top: the screen needs that long to settle. */
private const val LINE_WAIT_LEAST = 400L

/**
 * How long, in milliseconds, the auto scroll leaves a row of [row] pixels at the top of a page of
 * [page] pixels before moving past it, at [pace] seconds for a third of the page: the row's share
 * of that time, so a tall row (a system of tablature, the song's head) waits longer than a line
 * of words and the sheet moves down the page at one rate. Never longer than the whole [pace].
 */
internal fun lineWait(pace: Int, row: Int, page: Int): Long {
    val whole = pace * 1000L
    if (page <= 0) return whole
    return (whole * row * 3 / page).coerceIn(LINE_WAIT_LEAST.coerceAtMost(whole), whole)
}

/** How much of the screen's width the scrollbar and the sheet's own margins take. */
private val SHEET_MARGINS = 40.dp + 20.dp + 4.dp

/**
 * One song, read top to bottom: what it is, the key it is set in (and a way to move it), then
 * the sheet. A chord pressed shows where the fingers go. The bar at the foot starts the auto
 * scroll, which moves the sheet on a line at a time, and lists the song's chords. A pedal's keys
 * move the sheet a third of a page, and the sheet turns sideways with the phone. Opened
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
    // Tablature moves with the chords: every fret up or down by as much.
    val shownLines = remember(lines, song.transpose) { Tablature.movedLines(lines, song.transpose) }
    val hasTab = remember(lines) { lines.any { it is SheetLine.Tab } }
    // Tablature that fits the neck neither way stays as written while the chords move: said on the sheet.
    val tabAsWritten = remember(lines, song.transpose) { Tablature.asWritten(lines, song.transpose) }

    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var chord by remember { mutableStateOf<Chord?>(null) }
    var listing by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var running by remember(id) { mutableStateOf(false) }
    // The speed worked out from the song's length when the scroll was started, until − or + sets one.
    var worked by remember(id) { mutableStateOf(0) }
    val speed = (song.speed.takeIf { it > 0 } ?: worked.takeIf { it > 0 } ?: SPEED_USUAL).coerceIn(1, SPEED_SECONDS.size)
    val pace = SPEED_SECONDS[speed - 1]
    val state = remember(id) { LazyListState() }

    // A pedal's first press must turn the page. Android gives the first key pressed on a touched
    // screen to "leaving touch mode", and swallows it, unless something already has the focus: so
    // the sheet's own view takes it when the song opens and again whenever a dialog over it closes.
    // (The view only takes the focus when something in it can: the sheet's box is that thing.)
    val focus = remember { FocusRequester() }
    val covered = chord != null || listing || more
    LaunchedEffect(id, covered) {
        if (!covered) runCatching { focus.requestFocus() }
    }

    DisposableEffect(state) {
        Hands.onTurn = { forward -> scope.launch { if (forward) state.stepOn() else state.stepBack() } }
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
            delay(state.lineWait(pace))
            if (!state.lineOn()) running = false
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
                            if (song.speed == 0 && song.length > 0) worked = speedFor(song.length / (state.stepsLeft() + 1))
                            running = true
                        }, Modifier.weight(1f))
                        if (chords.isNotEmpty()) HouseButton(stringResource(R.string.chords_button), { listing = true }, Modifier.weight(1f))
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().focusRequester(focus).focusable()) {
            val measurer = rememberTextMeasurer()
            val columns = with(LocalDensity.current) {
                val one = measurer.measure("0", mono).size.width.coerceAtLeast(1)
                ((maxWidth - SHEET_MARGINS).toPx() / one).toInt().coerceAtLeast(12)
            }
            PagedColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 4.dp),
                // The row the foot cuts is drawn, and the next page opens on it whole (house rule,
                // 7 Nov 2026: the cut row rather than a gap at the bottom). A system of tablature
                // that did not fit whole used to leave half a page blank before it.
                showCutRow = true,
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
                // A song of tablature alone moves too: every fret up or down.
                if (chords.isNotEmpty() || hasTab) {
                    item(key = "key") {
                        Mover(
                            // With the song's key known, the key it is now in is said beside how far it moved.
                            label = Words.moved(
                                context, song.transpose, Chord.parse(song.key)?.transposed(song.transpose, flats)?.name,
                                chords = chords.isNotEmpty(),
                            ),
                            onDown = { store.set(id) { it.copy(transpose = wrap(it.transpose - 1)) } },
                            onUp = { store.set(id) { it.copy(transpose = wrap(it.transpose + 1)) } },
                        )
                    }
                }
                }
                var n = 0
                var noted = false
                for (line in shownLines) {
                    val key = "l${n++}"
                    // Once, above the first tablature, upright or sideways: the reader meets it before the frets.
                    if (tabAsWritten && !noted && line is SheetLine.Tab) {
                        noted = true
                        item(key = "tab-as-written") {
                            TextMMD(text = stringResource(R.string.tab_as_written), style = body, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                        }
                    }
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

/** Twelve semitones up is the same chords again, so the count runs from eleven down to eleven up. */
private fun wrap(semitones: Int): Int = if (semitones > 11 || semitones < -11) 0 else semitones

/**
 * Moves the sheet on by a third of a page (two rows at the least): the row a third of the way
 * down comes to the top, so the lines being played stay in view while the next ones arrive.
 * False when the last row is already shown.
 */
private suspend fun LazyListState.stepOn(): Boolean {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val last = info.totalItemsCount - 1
    val rows = info.visibleItemsInfo
    if (rows.isEmpty()) return false
    if (rows.any { it.index == last && it.offset + it.size <= height }) return false
    val first = rows.first().index
    // The first row that starts a third of the way down or lower, so a step is the same reach
    // down the page whether the rows are a title, a line of words or a system of tablature.
    val target = (rows.firstOrNull { it.offset >= height / 3 }?.index ?: (first + 1)).coerceAtLeast(first + 1).coerceAtMost(last)
    scrollToItem(target)
    return true
}

/**
 * Moves the sheet on by one row, the auto scroll's step: small enough to follow by eye, and the
 * top of the page is still always the top of a line. False when the last row is already shown.
 */
private suspend fun LazyListState.lineOn(): Boolean {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val last = info.totalItemsCount - 1
    val rows = info.visibleItemsInfo
    if (rows.isEmpty()) return false
    if (rows.any { it.index == last && it.offset + it.size <= height }) return false
    scrollToItem((rows.first().index + 1).coerceAtMost(last))
    return true
}

/** How long the auto scroll leaves the row now at the top there; see the function it calls. */
private fun LazyListState.lineWait(pace: Int): Long {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull() ?: return pace * 1000L
    return lineWait(pace, row.size, info.viewportSize.height - info.afterContentPadding)
}

/** How many rows one step moves, of [whole] rows on the page: a third of them, two at the least, never the whole page. */
internal fun stride(whole: Int): Int = (whole / 3).coerceAtLeast(2).coerceAtMost((whole - 1).coerceAtLeast(1))

/** Moves the sheet back by about as much as [stepOn] moves it on. False at the top. */
private suspend fun LazyListState.stepBack(): Boolean {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val rows = info.visibleItemsInfo
    val first = rows.firstOrNull()?.index ?: return false
    if (first == 0) return false
    val whole = rows.count { it.offset >= 0 && it.offset + it.size <= height }
    scrollToItem((first - stride(whole)).coerceAtLeast(0))
    return true
}

/** About how many steps of [stepOn] (thirds of a page, the auto scroll's measure of pace) are left before the last row is shown. */
private fun LazyListState.stepsLeft(): Int {
    val info = layoutInfo
    val height = info.viewportSize.height - info.afterContentPadding
    val last = info.totalItemsCount - 1
    val whole = info.visibleItemsInfo.filter { it.index <= last && it.offset >= 0 && it.offset + it.size <= height }
    val shown = whole.lastOrNull()?.index ?: return 0
    val each = stride(whole.size)
    return ((last - shown) + each - 1) / each
}

/** The key the sheet is shown in, with a − and a + either side to move it a semitone. */
@Composable
private fun Mover(label: String, onDown: () -> Unit, onUp: () -> Unit) {
    val down = stringResource(R.string.cd_key_down)
    val up = stringResource(R.string.cd_key_up)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp, end = 8.dp),
    ) {
        OutlinedButtonMMD(onClick = onDown, modifier = Modifier.size(48.dp).semantics { contentDescription = down }) {
            TextMMD(text = "−", style = MaterialTheme.typography.titleMedium)
        }
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        OutlinedButtonMMD(onClick = onUp, modifier = Modifier.size(48.dp).semantics { contentDescription = up }) {
            TextMMD(text = "+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A line of chords with no words under it. Each chord can be pressed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChordRow(line: SheetLine.Chords, style: TextStyle, shown: (Chord) -> Chord, onChord: (Chord) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        for (mark in line.marks) {
            when (mark) {
                is Mark.Of -> {
                    val c = shown(mark.chord)
                    // A chord alone on its line is a small thing to press: its target reaches a little above and below it.
                    TextMMD(text = c.name, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onChord(c) }.padding(vertical = 5.dp))
                }
                is Mark.Word -> TextMMD(text = mark.text, style = style, modifier = Modifier.padding(vertical = 5.dp))
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
 * A line of words with its chords over it, set the way a songbook sets one: the words run on as
 * a sentence and wrap like one, and each chord stands over the syllable it was written over,
 * reaching out over the words that follow when its name is wider than its syllable. Words are
 * only pushed apart where two chords would otherwise run into each other. A line of the wrapped
 * sentence with no chord on it is only as tall as its words. Pressing a chord or its syllable
 * shows the chord.
 */
@Composable
private fun SungRow(line: SheetLine.Sung, style: TextStyle, shown: (Chord) -> Chord, onChord: (Chord) -> Unit) {
    val words = remember(line) { Sheet.words(line) }
    // Flat, in order: every piece with the word it is part of.
    val plan = remember(words) { words.flatMapIndexed { w, word -> word.map { piece -> w to piece } } }
    androidx.compose.ui.layout.Layout(
        modifier = Modifier.padding(vertical = 2.dp),
        content = {
            // For every piece its words, then its chord if it has one.
            for ((_, piece) in plan) {
                val c = piece.chord?.let(shown)
                TextMMD(text = piece.text, style = style, softWrap = false, modifier = if (c != null) Modifier.clickable { onChord(c) } else Modifier)
                if (c != null) TextMMD(text = c.name, style = style, fontWeight = FontWeight.Bold, softWrap = false, modifier = Modifier.clickable { onChord(c) })
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        var m = 0
        val texts = ArrayList<androidx.compose.ui.layout.Placeable>()
        val chords = ArrayList<androidx.compose.ui.layout.Placeable?>()
        for ((_, piece) in plan) {
            texts += measurables[m++].measure(loose)
            chords += if (piece.chord != null) measurables[m++].measure(loose) else null
        }
        val places = sungPlaces(
            plan.indices.map { SungPiece(plan[it].first, texts[it].width, chords[it]?.width ?: -1) },
            constraints.maxWidth, space = 5.dp.roundToPx(), between = 8.dp.roundToPx(),
        )
        val textHeight = texts.maxOfOrNull { it.height } ?: 0
        val chordHeight = chords.maxOfOrNull { it?.height ?: 0 } ?: 0
        val lines = (places.maxOfOrNull { it.line } ?: 0) + 1
        val chorded = BooleanArray(lines)
        places.forEachIndexed { i, at -> if (chords[i] != null) chorded[at.line] = true }
        val tops = IntArray(lines)
        var y = 0
        for (l in 0 until lines) {
            tops[l] = y
            y += textHeight + if (chorded[l]) chordHeight else 0
        }
        layout(constraints.maxWidth, y) {
            places.forEachIndexed { i, at ->
                val top = tops[at.line]
                chords[i]?.place(at.chordX, top)
                texts[i].place(at.textX, top + if (chorded[at.line]) chordHeight else 0)
            }
        }
    }
}

/** One piece of a sung line as its layout sees it: which word it is part of, how wide its words are, and its chord (-1 for none). */
internal data class SungPiece(val word: Int, val text: Int, val chord: Int)

/** Where a piece goes: the line of the wrapped sentence, where its words start and where its chord does. */
internal data class SungPlace(val line: Int, val textX: Int, val chordX: Int)

/**
 * Sets the pieces of a sung line in a width: words one after another with [space] between, a
 * word never broken across lines, a chord over the start of its piece. A piece is moved right
 * only when its chord would otherwise start before the chord ahead of it has ended ([between]
 * clear of it). A word, with the chords on it, that will not fit on the line starts the next.
 */
internal fun sungPlaces(pieces: List<SungPiece>, width: Int, space: Int, between: Int): List<SungPlace> {
    val out = ArrayList<SungPlace>(pieces.size)
    var line = 0
    var x = 0
    var chordEnd = Int.MIN_VALUE / 2
    var i = 0
    while (i < pieces.size) {
        var end = i
        while (end < pieces.size && pieces[end].word == pieces[i].word) end++
        // The word tried at a place: where its pieces land, where its words end and where its last chord does.
        fun set(fromX: Int, fromChordEnd: Int, onLine: Int): Triple<List<SungPlace>, Int, Int> {
            var sx = fromX
            var ce = fromChordEnd
            val placed = ArrayList<SungPlace>()
            for (k in i until end) {
                val p = pieces[k]
                var px = sx
                if (p.chord >= 0) {
                    px = maxOf(px, ce + between)
                    ce = px + p.chord
                }
                placed += SungPlace(onLine, px, px)
                sx = px + p.text
            }
            return Triple(placed, sx, ce)
        }
        var tried = set(x, chordEnd, line)
        if (x > 0 && maxOf(tried.second, tried.third) > width) {
            line++
            chordEnd = Int.MIN_VALUE / 2
            tried = set(0, chordEnd, line)
        }
        out += tried.first
        x = tried.second + space
        chordEnd = tried.third
        i = end
    }
    return out
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
            // The name is what this is for: the field takes the focus, asked from inside the dialog a
            // frame after it is drawn, and the keyboard comes up.
            val nameField = remember { FocusRequester() }
            val keyboardNow = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { nameField.requestFocus() }
                keyboardNow?.show()
            }
            Field(stringResource(R.string.setlist_name), name, { name = it }, modifier = Modifier.focusRequester(nameField))
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
