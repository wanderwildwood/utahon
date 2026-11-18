package com.wanderwildwood.utahon.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.importer.Reader

/**
 * The other apps a song reaches: the browser for the page it came from, Tuner to tune by, and
 * anything at all for sharing. Each press that finds nothing to answer it says so rather than doing nothing.
 */
object Elsewhere {

    /** Text to any app that takes it, through the phone's own chooser. */
    fun share(context: Context, title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        if (!open(context, Intent.createChooser(send, null))) say(context, R.string.nothing_takes_text)
    }

    /**
     * The address a song's page is opened at: over https, as every page this app reads is (a
     * plain http one is asked for over https); null for anything that is not a web page, since
     * an address kept with a song may have come from a file.
     */
    fun pageAddress(url: String): String? =
        Reader.secure(url).takeIf { it.startsWith("https://", ignoreCase = true) && it.length > "https://".length }

    /** The page a song came from, in the browser. */
    fun page(context: Context, url: String) {
        val address = pageAddress(url) ?: return
        openLink(context, address, context.getString(R.string.about_no_browser_address, address.substring("https://".length)))
    }

    /** Tuner (onsa), which also keeps the beat. */
    private const val TUNER_APP = "com.wanderwildwood.onsa"

    /** Whether Tuner is on the phone. */
    fun hasTuner(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(TUNER_APP) != null

    /**
     * Tuner opened over the song, in this app's own stack of screens rather than as an app of
     * its own, so Back out of Tuner comes back to the song where it was left.
     */
    fun tuner(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(TUNER_APP)
        // The launcher's intent asks for a new task; the same screen asked for plainly does not.
        val intent = launch?.component?.let { Intent(Intent.ACTION_MAIN).setComponent(it) }
        if (intent == null || !open(context, intent)) say(context, R.string.no_tuner)
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun say(context: Context, text: Int) = Notice.say(context.getString(text))
}
