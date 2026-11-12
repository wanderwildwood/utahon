package com.wanderwildwood.utahon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Prefs
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Store
import com.wanderwildwood.utahon.music.Instrument
import com.wanderwildwood.utahon.sync.Cloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The few settings, and the songs in and out of a file: the whole songbook to keep somewhere
 * else, and a file of songs from another phone brought in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit, onAbout: () -> Unit, onOpen: (String) -> Unit, onNextcloud: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs.get(context) }
    val all by store.all.collectAsStateWithLifecycle()
    val instrument by prefs.instrument.collectAsStateWithLifecycle()
    val size by prefs.size.collectAsStateWithLifecycle()
    val stayOn by prefs.stayOn.collectAsStateWithLifecycle()
    val turn by prefs.turn.collectAsStateWithLifecycle()
    val cloud = remember { Cloud.get(context) }
    val account by cloud.account.collectAsStateWithLifecycle()

    val export = rememberLauncherForActivityResult(FilesCreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.export(Setlists.get(context).all.value).toByteArray()) } != null
                }.getOrDefault(false)
            }
            Elsewhere.say(context, if (ok) R.string.saved_to_file else R.string.save_failed)
        }
    }
    val bring = rememberLauncherForActivityResult(FilesOpenDocument(writable = false)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { bringIn(context, store, uri)?.let(onOpen) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
            item(key = "boxes") { Heading(stringResource(R.string.settings_boxes)) }
            item(key = "instrument") {
                HouseChoiceRow(
                    options = Instrument.entries.map { stringResource(instrumentName(it)) },
                    selected = Instrument.entries.indexOf(instrument),
                    onSelect = { prefs.setInstrument(Instrument.entries[it]) },
                    compact = true,
                )
            }
            item(key = "size-head") { Heading(stringResource(R.string.settings_size)) }
            item(key = "size") {
                HouseChoiceRow(
                    options = listOf(stringResource(R.string.size_small), stringResource(R.string.size_medium), stringResource(R.string.size_large)),
                    selected = size,
                    onSelect = { prefs.setSize(it) },
                    compact = true,
                )
            }
            item(key = "gap") { Spacer(Modifier.height(10.dp)) }
            item(key = "stay") {
                SwitchRow(stringResource(R.string.settings_stay_on), stayOn, onChange = { prefs.setStayOn(it) })
            }
            item(key = "turn") {
                SwitchRow(stringResource(R.string.settings_turn), turn, onChange = { prefs.setTurn(it) })
            }
            item(key = "nextcloud") {
                val a = account
                SettingRow(
                    stringResource(R.string.cloud_title),
                    if (a == null) stringResource(R.string.cloud_off) else stringResource(R.string.cloud_signed_in, a.user, a.server.removePrefix("https://")),
                    onClick = onNextcloud,
                )
            }
            item(key = "files") { Heading(stringResource(R.string.settings_files)) }
            item(key = "export") {
                SettingRow(
                    stringResource(R.string.export_all),
                    pluralStringResource(R.plurals.song_count, all.size, all.size),
                    onClick = if (all.isEmpty()) null else ({ export.launch("songbook-${LocalDate.now()}.json") }),
                )
            }
            item(key = "import") {
                SettingRow(stringResource(R.string.import_file), stringResource(R.string.import_file_note)) {
                    bring.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
            }
        }
    }
}
