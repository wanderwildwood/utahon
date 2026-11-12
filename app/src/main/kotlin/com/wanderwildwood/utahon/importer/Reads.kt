package com.wanderwildwood.utahon.importer

import com.wanderwildwood.utahon.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Pages being read. A read belongs to the app, not to the screen that asked for it: a second
 * link shared while the first is still coming replaces the screen, and the first song is saved
 * all the same when its page arrives.
 */
object Reads {

    /** What a read came to, and the id of the song it saved when it found one. */
    data class Done(val result: Reader.Result, val saved: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<String, Deferred<Done>>()

    /** The read of [url]: the one already under way, or a new one. */
    @Synchronized
    fun of(url: String, store: Store, read: (String) -> Reader.Result = Reader::read): Deferred<Done> {
        running[url]?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            val r = read(url)
            val saved = if (r is Reader.Result.Found) (store.byUrl(r.song.url) ?: store.byUrl(url) ?: store.save(r.song.copy(id = ""))).id else null
            Done(r, saved)
        }.also { running[url] = it }
    }
}
