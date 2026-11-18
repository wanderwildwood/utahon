package com.wanderwildwood.utahon.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.sync.Account
import com.wanderwildwood.utahon.sync.Cloud
import com.wanderwildwood.utahon.sync.LoginFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Nextcloud: signing in, and once in, the folder, a sync on demand and signing out. Signing in
 * is typed: the server, the login name and an app password made in Nextcloud. The server's own
 * page in the browser, as Nextcloud's apps use, is the second way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudScreen(cloud: Cloud, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val folder by cloud.folder.collectAsStateWithLifecycle()
    val state by cloud.state.collectAsStateWithLifecycle()
    val last by cloud.lastSync.collectAsStateWithLifecycle()

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // The sign-in itself, and the wait for the browser, are the app's (see Cloud.signing), so
    // Back or the screen built again does not lose them; only the first ask is the screen's.
    val signing by cloud.signing.collectAsStateWithLifecycle()
    var starting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var newFolder by remember(folder) { mutableStateOf(folder) }
    LaunchedEffect(signing.problem) {
        signing.problem?.let {
            problem = it
            cloud.problemSaid()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.cloud_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp), showCutRow = true) {
            item { Spacer(Modifier.height(8.dp)) }
            val a = account
            if (a != null) {
                item(key = "account") {
                    SettingRow(stringResource(R.string.cloud_account), stringResource(R.string.cloud_signed_in, a.user, a.server.removePrefix("https://")), onClick = null)
                }
                item(key = "sync") {
                    val status = when (state) {
                        Cloud.State.SYNCING -> stringResource(R.string.cloud_syncing)
                        Cloud.State.UNREACHABLE -> stringResource(R.string.cloud_failed_unreachable)
                        Cloud.State.REFUSED -> stringResource(R.string.cloud_failed_refused)
                        Cloud.State.FAILED -> stringResource(R.string.cloud_failed)
                        else -> if (last == 0L) stringResource(R.string.cloud_never) else stringResource(
                            R.string.cloud_last,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)),
                        )
                    }
                    SettingRow(stringResource(R.string.cloud_sync_now), status, onClick = { cloud.syncNow() })
                }
                item(key = "folder") {
                    Field(stringResource(R.string.cloud_folder), newFolder, { newFolder = it }, words = false)
                }
                if (newFolder.trim().trim('/') != folder) {
                    item(key = "use") { WideButton(stringResource(R.string.cloud_use_folder), filled = true) { cloud.setFolder(newFolder) } }
                }
                item(key = "folder_note") { Note(stringResource(R.string.cloud_folder_note)) }
                item(key = "out") {
                    ArmedRow(stringResource(R.string.cloud_sign_out), stringResource(R.string.cloud_sign_out_armed)) { cloud.signOut() }
                }
            } else {
                item(key = "explain") { Note(stringResource(R.string.cloud_explain)) }
                item(key = "server") {
                    Field(stringResource(R.string.cloud_server), server, { server = it; problem = null }, hint = stringResource(R.string.cloud_server_hint), url = true)
                }
                // The typed way first: it leans on nothing but the three things typed. The
                // browser's way is the second button, for a server that asks for more at its door.
                item(key = "user") { Field(stringResource(R.string.cloud_user), user, { user = it; problem = null }, words = false) }
                item(key = "password") {
                    Field(stringResource(R.string.cloud_app_password), password, { password = it; problem = null }, secret = true)
                }
                item(key = "password_note") { Note(stringResource(R.string.cloud_app_password_note)) }
                problem?.let { p -> item(key = "problem") { Note(stringResource(p)) } }
                if (starting || signing.browser) item(key = "waiting") { Note(stringResource(R.string.cloud_waiting)) }
                item(key = "sign_in") {
                    WideButton(stringResource(R.string.cloud_sign_in), filled = true) {
                        if (server.isBlank() || user.isBlank() || password.isBlank()) {
                            problem = R.string.cloud_fill_all
                            return@WideButton
                        }
                        if (LoginFlow.isPlain(server)) {
                            problem = R.string.cloud_needs_https
                            return@WideButton
                        }
                        cloud.signInLater(Account(LoginFlow.normalise(server), user.trim(), password.trim()))
                    }
                }
                item(key = "browser") {
                    WideButton(stringResource(R.string.cloud_browser)) {
                        if (server.isBlank()) {
                            problem = R.string.cloud_server_first
                            return@WideButton
                        }
                        if (LoginFlow.isPlain(server)) {
                            problem = R.string.cloud_needs_https
                            return@WideButton
                        }
                        if (starting || signing.browser) return@WideButton
                        problem = null
                        // The server as it was when the button was pressed: the password the
                        // browser hands back belongs to that one, whatever the field says later.
                        val asked = server
                        starting = true
                        scope.launch {
                            try {
                                val started = runCatching { withContext(Dispatchers.IO) { LoginFlow.start(asked) } }.getOrNull()
                                if (started == null) {
                                    problem = R.string.cloud_unreachable
                                    return@launch
                                }
                                val opened = isRealBrowserFor(context, started.loginUrl) &&
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                                    }.isSuccess
                                if (!opened) {
                                    problem = R.string.cloud_no_browser
                                    return@launch
                                }
                                cloud.awaitBrowser(started, asked)
                            } finally {
                                starting = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A line or two of explanation between the rows. */
@Composable
private fun Note(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
}
