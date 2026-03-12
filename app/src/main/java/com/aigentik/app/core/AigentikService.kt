/*
 * Copyright (C) 2024 Aigentik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aigentik.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R
import com.aigentik.app.ai.AgentLLMFacade
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.data.AppDB
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailHistoryClient
import com.aigentik.app.system.ConnectionWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Foreground service that keeps the Aigentik process alive on Samsung and other
 * aggressive battery-optimizer OEMs.
 *
 * Without this service, the OS kills the app process within seconds of the user
 * leaving the screen. When the process dies, the NotificationListenerService
 * (AigentikNotificationListener) is unbound and incoming SMS/RCS notifications
 * are never received.
 *
 * Key design choices ported from aigentik-android AigentikService:
 * - PARTIAL_WAKE_LOCK: prevents Samsung from throttling background CPU during
 *   LLM inference (without it, inference goes from ~30s to 5+ minutes).
 * - START_STICKY: Android restarts this service if it's killed.
 * - foregroundServiceType="dataSync": declared in manifest, correct type for
 *   background network + inference workloads.
 *
 * This is a Phase 1 stub — engines (MessageEngine, ContactEngine, etc.) will be
 * wired in Step 4/5 of the aigentik-fuse.md implementation plan.
 */
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID      = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG     = "AigentikService"
        private const val WAKE_TAG = "aigentik:inference"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AigentikService onCreate")

        // Init settings FIRST — every engine reads from AigentikSettings
        AigentikSettings.init(this)

        // PARTIAL_WAKE_LOCK — keeps CPU running at full speed in background.
        // Samsung throttles background processes to ~20% CPU without this,
        // increasing LLM inference time from ~30s to 5+ minutes.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        wakeLock?.acquire()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("${AigentikSettings.agentName} is active"))

        // Load channel states from persisted settings
        ChannelManager.loadFromSettings()

        // Restore Google account from persistent sign-in (needed before EmailMonitor)
        GoogleAuthManager.initFromStoredAccount(this)

        // Start OAuth session watchdog
        ConnectionWatchdog.start(this)

        Log.i(TAG, "Foreground service started — process will stay alive")

        // Stage 2: Initialize agent engines (SMS/RCS + Gmail)
        initAgentEngines()
    }

    private fun initAgentEngines() {
        // ContactEngine init (Room I/O — must be on background thread)
        serviceScope.launch {
            try {
                ContactEngine.init(this@AigentikService)
                Log.i(TAG, "ContactEngine ready — ${ContactEngine.getCount()} contacts")
            } catch (e: Exception) {
                Log.e(TAG, "ContactEngine init failed: ${e.message}")
            }
        }

        // Init EmailRouter and EmailMonitor (no I/O — immediate)
        // EmailRouter must be initialized BEFORE MessageEngine.configure() so that
        // if a Gmail notification arrives during startup, routeReply() has appContext set.
        EmailRouter.init(this)
        EmailMonitor.init(this)

        // Configure MessageEngine (no I/O — immediate)
        MessageEngine.configure(
            context       = this,
            adminNumber   = AigentikSettings.adminNumber,
            ownerName     = AigentikSettings.ownerName,
            agentName     = AigentikSettings.agentName,
            ownerNotifier = { msg ->
                Log.i(TAG, "ownerNotifier: $msg")
                // TODO Stage 2: post to owner notification channel
            },
            wakeLock      = wakeLock,
        )

        // Prime Gmail historyId (IO — launch on background thread)
        // If not signed in, primeHistoryId returns NO_TOKEN immediately — no harm done.
        // On first sign-in, EmailMonitor's listUnread fallback handles the initial batch.
        serviceScope.launch {
            val result = GmailHistoryClient.primeHistoryId(this@AigentikService)
            Log.i(TAG, "GmailHistoryClient.primeHistoryId: $result")
        }

        // Load LLM model (slow — launch on IO, app is already running in foreground)
        serviceScope.launch {
            try {
                val modelPath = resolveModelPath()
                if (modelPath.isNotBlank()) {
                    val ok = AgentLLMFacade.loadModel(modelPath)
                    if (ok) {
                        Log.i(TAG, "AgentLLMFacade ready — model: $modelPath")
                    } else {
                        Log.w(TAG, "AgentLLMFacade load returned false — no model at: $modelPath")
                    }
                } else {
                    Log.w(TAG, "No model found — download a model in the app, then restart")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AgentLLMFacade load failed: ${e.message}")
            }
        }
    }

    // Returns the model path for AgentLLMFacade.
    // Priority: AigentikSettings.modelPath (user-overridden) → AppDB first downloaded model.
    // Keeps AigentikSettings.modelPath in sync so future restarts skip the DB lookup.
    private suspend fun resolveModelPath(): String {
        // 1. Explicit override already set
        val stored = AigentikSettings.modelPath
        if (stored.isNotBlank()) return stored

        // 2. Auto-detect from SmolChat's model library (AppDB)
        return try {
            val appDB = GlobalContext.get().get<AppDB>()
            val models = appDB.getModelsList()
            val path = models.firstOrNull()?.path ?: ""
            if (path.isNotBlank()) {
                AigentikSettings.modelPath = path  // cache for future restarts
                Log.i(TAG, "Auto-resolved model path from AppDB: $path")
            }
            path
        } catch (e: Exception) {
            Log.w(TAG, "Could not auto-resolve model path from AppDB: ${e.message}")
            ""
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS kills this service, it will be restarted automatically
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "AigentikService onDestroy — service is being stopped")
        ConnectionWatchdog.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aigentik")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aigentik Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Aigentik running in the background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
