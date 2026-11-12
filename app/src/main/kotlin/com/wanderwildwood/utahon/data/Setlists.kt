package com.wanderwildwood.utahon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Songs in the order they are to be played: an evening's set, a lesson, the ones being learned. */
data class Setlist(val id: String, val name: String, val songs: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("songs", JSONArray(songs))

    companion object {
        fun fromJson(o: JSONObject): Setlist? {
            val name = o.optString("name").trim()
            if (name.isEmpty()) return null
            val a = o.optJSONArray("songs")
            val songs = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            return Setlist(o.optString("id"), name, songs)
        }

        /** The setlists in a saved songbook, if it carries any. */
        fun allIn(json: String): List<Setlist> = runCatching {
            val a = JSONObject(json.trim().removePrefix("﻿")).optJSONArray("setlists") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }
}

/** Where a song stands in a setlist, and what comes after it. */
data class Place(val setlist: String, val at: Int)

/** The setlists, kept in one small file beside the songs. A song deleted from the book simply drops out of them. */
class Setlists private constructor(context: Context) {

    private val file = File(context.filesDir, "setlists.json")
    private val prefs = context.getSharedPreferences("setlists", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())

    /** Changes whenever the setlists are changed here; a sync tells by it whether to send them. */
    @Volatile
    var stamp: String = prefs.getString("stamp", "").orEmpty()
        private set

    /** Called after every change made here on the phone, not after a sync's own. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Every setlist, in the order they were made. */
    val all: StateFlow<List<Setlist>> = _all

    private fun load(): List<Setlist> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Setlist::fromJson) }
    }.getOrDefault(emptyList())

    fun get(id: String): Setlist? = _all.value.firstOrNull { it.id == id }

    @Synchronized
    private fun keep(list: List<Setlist>) {
        write(list)
        restamp()
        onChanged?.invoke()
    }

    private fun restamp(): String {
        stamp = UUID.randomUUID().toString()
        prefs.edit().putString("stamp", stamp).apply()
        return stamp
    }

    /** Setlists from a sync, in place of what is here, only if nothing changed here since [expect]; the new stamp, or null. */
    @Synchronized
    fun putSynced(list: List<Setlist>, expect: String): String? {
        if (stamp != expect) return null
        write(list)
        return restamp()
    }

    private fun write(list: List<Setlist>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString(1))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _all.value = list
    }

    fun add(name: String): Setlist {
        val made = Setlist(UUID.randomUUID().toString(), name.trim())
        keep(_all.value + made)
        return made
    }

    fun change(id: String, how: (Setlist) -> Setlist) = keep(_all.value.map { if (it.id == id) how(it).copy(id = id) else it })

    fun delete(id: String) = keep(_all.value.filterNot { it.id == id })

    /** Setlists from a saved songbook: one with the id of a setlist already here replaces it. */
    fun bringIn(lists: List<Setlist>) {
        if (lists.isEmpty()) return
        val byId = LinkedHashMap(_all.value.associateBy { it.id })
        for (l in lists) {
            val kept = if (l.id.isEmpty()) l.copy(id = UUID.randomUUID().toString()) else l
            byId[kept.id] = kept
        }
        keep(byId.values.toList())
    }

    companion object {
        @Volatile
        private var lists: Setlists? = null

        fun get(context: Context): Setlists =
            lists ?: synchronized(this) { lists ?: Setlists(context.applicationContext).also { lists = it } }

        /** [list] with the song at [from] moved to [to]. */
        fun moved(list: List<String>, from: Int, to: Int): List<String> {
            if (from !in list.indices || to !in list.indices || from == to) return list
            val m = list.toMutableList()
            m.add(to, m.removeAt(from))
            return m
        }
    }
}
