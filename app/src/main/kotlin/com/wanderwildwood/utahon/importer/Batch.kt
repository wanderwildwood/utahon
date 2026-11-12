package com.wanderwildwood.utahon.importer

import com.wanderwildwood.utahon.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Several pages read one after another, from a list of addresses pasted or shared at once. The
 * reading belongs to the app, not to the screen that shows it, so it goes on if the reader
 * leaves; each page is asked for on its own, with a pause between, the way a person would open
 * them.
 */
object Batch {

    enum class State { WAITING, READING, SAVED, ALREADY, NO_SONG, FAILED }

    /** One address of the list: how it is getting on, the song it became, the site's answer if it failed. */
    data class Item(val url: String, val state: State = State.WAITING, val title: String = "", val code: Int = 0, val saved: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /** Whether a list is still being read. */
    val running: Boolean get() = job?.isActive == true

    /** Starts on [urls]; a list already being read has them added to its end. */
    @Synchronized
    fun start(urls: List<String>, store: Store, read: (String) -> Reader.Result = Reader::read, pauseMs: Long = 1_500) {
        val fresh = urls.filter { u -> _items.value.none { it.url == u } || !running }
        _items.value = (if (running) _items.value else emptyList()) + fresh.distinct().map { Item(it) }
        if (running) return
        job = scope.launch {
            while (true) {
                val next = _items.value.firstOrNull { it.state == State.WAITING } ?: break
                val known = store.byUrl(next.url)
                if (known != null) {
                    set(next.url) { it.copy(state = State.ALREADY, title = known.title, saved = known.id) }
                    continue
                }
                set(next.url) { it.copy(state = State.READING) }
                val done = runCatching { Reads.of(next.url, store, read).await() }.getOrNull()
                set(next.url) {
                    when (val r = done?.result) {
                        is Reader.Result.Found -> it.copy(state = State.SAVED, title = r.song.title, saved = done.saved)
                        is Reader.Result.NoSong -> it.copy(state = State.NO_SONG, title = r.title)
                        is Reader.Result.Failed -> it.copy(state = State.FAILED, code = r.code)
                        null -> it.copy(state = State.FAILED)
                    }
                }
                delay(pauseMs)
            }
        }
    }

    private fun set(url: String, change: (Item) -> Item) {
        _items.value = _items.value.map { if (it.url == url) change(it) else it }
    }
}
