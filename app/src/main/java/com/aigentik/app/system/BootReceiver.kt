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

package com.aigentik.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aigentik.app.core.AigentikService

/**
 * Restarts AigentikService after device reboot, package replace, or Samsung QuickBoot.
 * Without this, the service must be restarted manually by opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> true
            else -> false
        }
        if (!shouldStart) return

        Log.i(TAG, "Boot/replace detected (${intent.action}) — starting AigentikService")
        try {
            context.startForegroundService(Intent(context, AigentikService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AigentikService on boot: ${e.message}")
        }
    }
}
