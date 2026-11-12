package com.wanderwildwood.utahon.ui

import android.content.Context
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Song

/** The short lines the screens say about a song. */
object Words {

    /** The six strings of a guitar as they are usually tuned, which goes without saying. */
    private const val STANDARD = "E A D G B E"

    /** Under a song's name: what kind of sheet it is, its capo, its tuning when it is not the usual, its key. */
    fun about(context: Context, song: Song): String = listOfNotNull(
        song.kind.takeIf { it.isNotBlank() },
        song.capo.takeIf { it > 0 }?.let { context.getString(R.string.capo_fret, it) },
        song.tuning.takeIf { showsTuning(song) }?.let { context.getString(R.string.tuning_is, it) },
        song.key.takeIf { it.isNotBlank() }?.let { context.getString(R.string.key_is, it) },
    ).joinToString(" · ")

    /** Whether a song's tuning is worth a word: it has one, and it is not the usual. */
    fun showsTuning(song: Song): Boolean = song.tuning.isNotBlank() && song.tuning != STANDARD

    /** How far the chords have been moved from the way the sheet was written. */
    fun moved(context: Context, semitones: Int, key: String? = null): String {
        val how = when {
            semitones == 0 -> return context.getString(R.string.key_as_written)
            semitones > 0 -> context.resources.getQuantityString(R.plurals.key_up, semitones, semitones)
            else -> context.resources.getQuantityString(R.plurals.key_down, -semitones, -semitones)
        }
        return if (key.isNullOrBlank()) how else context.getString(R.string.key_moved_to, how, key)
    }

    /** A song in one line: its title, and who it is by. */
    fun heading(song: Song): String =
        if (song.artist.isBlank()) song.title else "${song.title} (${song.artist})"
}
