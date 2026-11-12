package com.wanderwildwood.utahon.ui

/**
 * What an open song asks of the activity around it, since both hands are on an instrument: to
 * take page turns from keys (a pedal), and to turn the screen with the phone. Null while no
 * song is open.
 */
object Hands {
    /** Moves the open sheet on (true) or back (false) half a page. */
    @Volatile
    var onTurn: ((Boolean) -> Unit)? = null

    /** Set by the activity: starts or stops turning the screen with the phone. */
    @Volatile
    var turning: ((Boolean) -> Unit)? = null
}
