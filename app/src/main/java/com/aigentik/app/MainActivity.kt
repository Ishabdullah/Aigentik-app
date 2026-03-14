/*
 * Copyright (C) 2024 Shubham Panchal
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

package com.aigentik.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.llm.ModelsRepository
import com.aigentik.app.ui.screens.agent_settings.AgentSettingsActivity
import com.aigentik.app.ui.screens.chat.ChatActivity
import com.aigentik.app.ui.screens.model_download.DownloadModelActivity
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val modelsRepository by inject<ModelsRepository>()

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the foreground service to keep the process alive on Samsung devices.
        // Without this, the OS kills the app within seconds of leaving the screen,
        // which unbinds AigentikNotificationListener and drops all incoming messages.
        startForegroundService(Intent(this, AigentikService::class.java))
        Log.i(TAG, "AigentikService start requested")

        // Ensure settings are readable before checking isConfigured.
        // AigentikService also calls init() but runs asynchronously after startForegroundService().
        AigentikSettings.init(this)

        // Request battery optimization exemption — lets the service and notification
        // listener stay alive without Samsung's aggressive background killing.
        // User sees a system dialog; we don't force it, just request once.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
            }
        }

        // Redirect user to the DownloadModelActivity if no models are available
        // as the app requires at least one model to function
        val models = runBlocking { modelsRepository.getAvailableModelsList() }
        if (models.isEmpty()) {
            Intent(this, DownloadModelActivity::class.java).apply {
                startActivity(this)
                finish()
            }
        } else if (!AigentikSettings.isConfigured) {
            // First run: send user to settings to configure name, phone number, and channels.
            Intent(this, AgentSettingsActivity::class.java).apply {
                startActivity(this)
                finish()
            }
        } else {
            Intent(this, ChatActivity::class.java).apply {
                startActivity(this)
                finish()
            }
        }
    }
}
