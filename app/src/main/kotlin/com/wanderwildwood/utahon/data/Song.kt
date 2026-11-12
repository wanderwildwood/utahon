package com.wanderwildwood.utahon.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A song as it is kept: who and what, and the sheet itself as plain text, chords on their own
 * lines over the words, exactly as it was typed or saved from a page. Nothing is worked out
 * ahead of time; the screen reads the text each time it shows it.
 *
 * [transpose] is how many semitones the reader has moved it, and [speed] how fast the auto
 * scroll goes, 1 to 9 (0 for the usual); both are the reader's own and travel with the song; [length] is how long the song runs, in seconds, when the reader has said, so the
 * auto scroll can pace itself. [kind] is what the page called it ("Chords", "Tab", "Ukulele Chords"), when one did.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val text: String = "",
    val kind: String = "",
    val capo: Int = 0,
    val tuning: String = "",
    val key: String = "",
    val url: String = "",
    val transpose: Int = 0,
    val speed: Int = 0,
    val length: Int = 0,
    val created: String = "",
    val modified: String = "",
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.putIf("artist", artist)
        o.put("text", text)
        o.putIf("kind", kind)
        if (capo != 0) o.put("capo", capo)
        o.putIf("tuning", tuning)
        o.putIf("key", key)
        o.putIf("url", url)
        if (transpose != 0) o.put("transpose", transpose)
        if (speed != 0) o.put("speed", speed)
        if (length != 0) o.put("length", length)
        o.putIf("created", created)
        o.putIf("modified", modified)
        return o
    }

    private fun JSONObject.putIf(key: String, value: String) {
        if (value.isNotEmpty()) put(key, value)
    }

    companion object {
        fun fromJson(o: JSONObject): Song? {
            val title = o.optString("title")
            val text = o.optString("text")
            if (title.isBlank() && text.isBlank()) return null
            return Song(
                id = o.optString("id"),
                title = title,
                artist = o.optString("artist"),
                text = text,
                kind = o.optString("kind"),
                capo = o.optInt("capo", 0).coerceIn(0, 24),
                tuning = o.optString("tuning"),
                key = o.optString("key"),
                url = o.optString("url"),
                transpose = o.optInt("transpose", 0).coerceIn(-11, 11),
                speed = o.optInt("speed", 0).coerceIn(0, 9),
                length = o.optInt("length", 0).coerceIn(0, 3600),
                created = o.optString("created"),
                modified = o.optString("modified"),
            )
        }

        /** The songs in a file saved from here: one song, or a songbook of many. */
        fun allIn(json: String): List<Song> = runCatching {
            val o = JSONObject(json.trim().removePrefix("﻿"))
            val many = o.optJSONArray("songs")
            if (many != null) (0 until many.length()).mapNotNull { many.optJSONObject(it)?.let(::fromJson) }
            else listOfNotNull(fromJson(o))
        }.getOrDefault(emptyList())
    }
}

/** Every song as one document, for a file, with the setlists they are in. */
fun songbookDocument(songs: List<Song>, setlists: List<Setlist> = emptyList()): JSONObject {
    val o = JSONObject().put("songbook", 1).put("songs", JSONArray().also { a -> songs.forEach { a.put(it.toJson()) } })
    if (setlists.isNotEmpty()) o.put("setlists", JSONArray().also { a -> setlists.forEach { a.put(it.toJson()) } })
    return o
}

/** A length typed as minutes and seconds ("3:40"), or as seconds alone, in seconds; 0 when it is neither. */
fun lengthFrom(text: String): Int {
    val t = text.trim()
    val parts = t.split(':')
    return when (parts.size) {
        1 -> parts[0].toIntOrNull() ?: 0
        2 -> (parts[0].toIntOrNull() ?: return 0) * 60 + (parts[1].toIntOrNull() ?: return 0)
        else -> 0
    }.coerceIn(0, 3600)
}

/** Seconds as minutes and seconds, "3:40"; nothing for none. */
fun lengthText(seconds: Int): String = if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)
