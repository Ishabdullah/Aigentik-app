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

package com.aigentik.app.sms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

/**
 * Koin-managed singleton that holds incoming SMS state.
 * Scoped to the application lifecycle via @Single, replacing the
 * previous companion-object static MutableStateFlow in SMSReceiver
 * which was accessible globally and could not be lifecycle-scoped.
 */
@Single
class SMSRepository {
    private val _incomingSMS = MutableStateFlow<List<SMSMessage>>(emptyList())
    val incomingSMS = _incomingSMS.asStateFlow()

    fun addMessages(messages: List<SMSMessage>) {
        _incomingSMS.value = _incomingSMS.value + messages
    }

    fun clearMessages() {
        _incomingSMS.value = emptyList()
    }
}
