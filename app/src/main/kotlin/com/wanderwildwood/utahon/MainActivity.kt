package com.wanderwildwood.utahon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.utahon.importer.Reader
import com.wanderwildwood.utahon.sync.Cloud
import com.wanderwildwood.utahon.ui.Hands
import com.wanderwildwood.utahon.ui.Incoming
import com.wanderwildwood.utahon.ui.SongbookApp
import com.wanderwildwood.utahon.ui.houseTypography
import com.wanderwildwood.utahon.ui.monochrome
import com.wanderwildwood.utahon.ui.ArmedScope

class MainActivity : ComponentActivity() {

    /** What the latest intent brought, until the app has taken it. */
    private val incoming = mutableStateOf<Incoming?>(null)

    private val turner = TurnWithPhone(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only an open song turns with the phone; it says when it opens and closes.
        turner.set(false)
        Hands.turning = { on -> turner.set(on) }
        if (savedInstanceState == null) incoming.value = incomingFrom(intent)
        setContent {
            // A steady cursor: a blinking one is a repaint twice a second on an e-ink panel
            // for as long as a field has focus.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                ThemeMMD(colorScheme = monochrome, typography = houseTypography) {
                    ArmedScope { SongbookApp(incoming.value, onTaken = { incoming.value = null }) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Nothing happens here unless the reader has signed in to a Nextcloud.
        Cloud.get(this).onOpen()
    }

    override fun onResume() {
        super.onResume()
        turner.resume()
    }

    override fun onPause() {
        turner.pause()
        super.onPause()
    }

    override fun onDestroy() {
        Hands.turning = null
        super.onDestroy()
    }

    /**
     * A page-turn pedal is a keyboard with two keys: most send Page Down and Page Up, some the
     * arrows or the space bar. While a song is open those move the sheet; anywhere else, and in
     * any dialog (a window of its own), the keys do what they always do.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> true
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> false
            else -> null
        }
        val turn = Hands.onTurn
        if (forward != null && turn != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) turn(forward)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFrom(intent)?.let { incoming.value = it }
    }

    /**
     * A page shared from the browser (its address, perhaps with its title), a sheet's words shared
     * from anywhere else, or a file of songs opened from Cabinet or shared from another app.
     */
    private fun incomingFrom(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { return Incoming.File(it) }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                // Several links are a list to read through, which the app takes as text and sorts out.
                Reader.addressIn(text)?.takeIf { Reader.isLinkShare(text) && !Reader.isLinkList(text) }?.let { Incoming.Link(it) }
                    ?: Incoming.Text(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") Incoming.Link(uri.toString()) else Incoming.File(uri)
            }
            else -> null
        }
    }
}
