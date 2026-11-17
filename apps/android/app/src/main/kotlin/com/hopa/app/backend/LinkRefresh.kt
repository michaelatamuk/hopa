package com.hopa.app.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides when a new push token may reopen the gateway link. A live link is
 * never closed underneath a run: the refresh waits for [onRunFinished]. With
 * no run and no reason to hold a socket open, the refresh is parked for the
 * next connect instead of opening one in the background.
 */
internal class LinkRefresh(
    private val scope: CoroutineScope,
    private val gate: Mutex,
    private val runs: () -> Int,
    private val mayOpen: () -> Boolean,
    private val closeLink: suspend () -> Unit,
    private val open: suspend () -> Unit,
) {
    @Volatile
    private var pending = false

    /** A new token arrived: reopen now when that is safe, otherwise park it. */
    fun request() {
        if (runs() > 0 || !mayOpen()) {
            pending = true
            return
        }
        reopen()
    }

    /**
     * The last run ended. In the foreground the parked refresh runs now; in the
     * background the session's stop rules apply and the next connect sends the
     * new token. Returns true when a new link is being opened.
     */
    fun onRunFinished(foreground: Boolean): Boolean {
        if (!pending || runs() > 0 || !foreground) {
            return false
        }
        pending = false
        reopen()
        return true
    }

    /** A link was built with the latest token: nothing is left to refresh. */
    fun onConnected() {
        pending = false
    }

    private fun reopen() {
        scope.launch {
            val closed = gate.withLock {
                if (runs() > 0) {
                    false
                } else {
                    closeLink()
                    true
                }
            }
            if (closed) {
                open()
            } else {
                pending = true
            }
        }
    }
}
