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

package com.aigentik.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

private var title = ""
private var text = ""
private var positiveButtonText = ""
private var negativeButtonText = ""
// Nullable to allow clearing after use, preventing lambda memory leaks
private var positiveButtonOnClick: (() -> Unit)? = null
private var negativeButtonOnClick: (() -> Unit)? = null
private val alertDialogShowStatus = mutableStateOf(false)

@Composable
fun AppAlertDialog() {
    val visible by remember { alertDialogShowStatus }
    if (visible) {
        AlertDialog(
            title = { Text(text = title) },
            text = { Text(text = text) },
            onDismissRequest = { /* All alert dialogs are non-cancellable */ },
            confirmButton = {
                TextButton(
                    onClick = {
                        alertDialogShowStatus.value = false
                        val onClick = positiveButtonOnClick
                        // Null out before invoking to release captured references
                        positiveButtonOnClick = null
                        negativeButtonOnClick = null
                        onClick?.invoke()
                    }
                ) {
                    Text(text = positiveButtonText)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        alertDialogShowStatus.value = false
                        val onClick = negativeButtonOnClick
                        // Null out before invoking to release captured references
                        positiveButtonOnClick = null
                        negativeButtonOnClick = null
                        onClick?.invoke()
                    }
                ) {
                    Text(text = negativeButtonText)
                }
            },
        )
    }
}

fun createAlertDialog(
    dialogTitle: String,
    dialogText: String,
    dialogPositiveButtonText: String,
    dialogNegativeButtonText: String?,
    onPositiveButtonClick: (() -> Unit),
    onNegativeButtonClick: (() -> Unit)?,
) {
    title = dialogTitle
    text = dialogText
    positiveButtonOnClick = onPositiveButtonClick
    negativeButtonOnClick = onNegativeButtonClick
    positiveButtonText = dialogPositiveButtonText
    negativeButtonText = dialogNegativeButtonText ?: ""
    alertDialogShowStatus.value = true
}
