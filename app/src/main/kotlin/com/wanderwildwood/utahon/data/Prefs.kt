package com.wanderwildwood.utahon.data

import android.content.Context
import com.wanderwildwood.utahon.music.Instrument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few settings: which instrument the chord boxes are drawn for, how large the sheet is set, whether the screen stays on and turns. */
class Prefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _instrument = MutableStateFlow(Instrument.of(prefs.getString("instrument", null)))
    val instrument: StateFlow<Instrument> = _instrument

    /** 0 is the usual size, 1 and 2 the steps above it. */
    private val _size = MutableStateFlow(prefs.getInt("size", 0).coerceIn(0, 2))
    val size: StateFlow<Int> = _size

    private val _stayOn = MutableStateFlow(prefs.getBoolean("stay_on", true))
    val stayOn: StateFlow<Boolean> = _stayOn

    /** Whether an open song turns sideways when the phone is turned. */
    private val _turn = MutableStateFlow(prefs.getBoolean("turn", true))
    val turn: StateFlow<Boolean> = _turn

    fun setTurn(on: Boolean) {
        prefs.edit().putBoolean("turn", on).apply()
        _turn.value = on
    }

    fun setInstrument(i: Instrument) {
        prefs.edit().putString("instrument", i.id).apply()
        _instrument.value = i
    }

    fun setSize(s: Int) {
        prefs.edit().putInt("size", s.coerceIn(0, 2)).apply()
        _size.value = s.coerceIn(0, 2)
    }

    fun setStayOn(on: Boolean) {
        prefs.edit().putBoolean("stay_on", on).apply()
        _stayOn.value = on
    }

    companion object {
        @Volatile
        private var prefs: Prefs? = null

        fun get(context: Context): Prefs =
            prefs ?: synchronized(this) { prefs ?: Prefs(context.applicationContext).also { prefs = it } }
    }
}
