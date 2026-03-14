package com.aigentik.app.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.ui.screens.agent_settings.AgentSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ConnectionWatchdog v3.0 — OAuth health check + re-auth notification
// v3.0: Implements H-5 — checks OAuth token every 30 minutes. If the token is invalid
//       (expired, revoked, or user signed out from another device), posts a system notification
//       directing the user to AgentSettingsActivity to re-authorize.
// v2.2: isRunning marked @Volatile (M-5) to ensure stop() is visible across threads.
object ConnectionWatchdog {

    private const val TAG               = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private const val NOTIF_CHANNEL_ID  = "aigentik_alerts"
    private const val NOTIF_ID_REAUTH   = 2001

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isRunning  = false
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started — interval=${CHECK_INTERVAL_MS / 60_000}min")
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

    private suspend fun check() {
        val ctx = appContext ?: return

        // Only check if the user was previously signed in
        if (!GoogleAuthManager.isSignedIn(ctx)) {
            Log.d(TAG, "Connection check — not signed in, skipping")
            return
        }

        val token = GoogleAuthManager.getFreshToken(ctx)
        if (token == null) {
            Log.w(TAG, "OAuth token invalid — posting re-auth notification")
            postReAuthNotification(ctx)
        } else {
            Log.d(TAG, "Connection check — OAuth OK")
        }
    }

    private fun postReAuthNotification(ctx: Context) {
        val tapIntent = Intent(ctx, AgentSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Create alert channel if not already created
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Aigentik Alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Urgent alerts requiring user action (e.g. re-authorization)"
            }
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aigentik: Gmail sign-in required")
            .setContentText("Email auto-reply is paused. Tap to re-authorize Gmail access.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Email and Google Voice auto-reply has stopped because Gmail authorization has expired. Tap to sign in again."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(NOTIF_ID_REAUTH, notif)
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "ConnectionWatchdog stopped")
    }
}
