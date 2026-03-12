package com.aigentik.app.system

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ConnectionWatchdog v2.2 — ported from aigentik-android, OAuth checks stubbed for Stage 2
// Stage 2: uncomment GoogleAuthManager checks and add SettingsActivity notification intent
object ConnectionWatchdog {

    private const val TAG               = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning  = false
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started")
        scope.launch {
            while (isActive && isRunning) {
                delay(CHECK_INTERVAL_MS)
                check()
            }
        }
    }

    fun checkNow() {
        scope.launch { check() }
    }

    private fun check() {
        Log.d(TAG, "Connection check — OK")
        // TODO Stage 2: check GoogleAuthManager.isSignedIn() and post re-auth notification if lost
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "ConnectionWatchdog stopped")
    }
}
