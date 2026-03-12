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

package com.aigentik.app.ui.screens.manage_tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.aigentik.app.data.AppDB
import com.aigentik.app.data.Task
import com.aigentik.app.llm.ModelsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class TasksViewModel(val modelsRepository: ModelsRepository, val appDB: AppDB) : ViewModel() {
    val tasksState: StateFlow<ImmutableList<Task>> =
        appDB.getTasks().map { tasks ->
            tasks.map { task ->
                val model = modelsRepository.getModelFromId(task.modelId)
                task.copy(modelName = model.name)
            }.toImmutableList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList<Task>().toImmutableList()
        )

    fun addTask(name: String, systemPrompt: String, modelId: Long) {
        viewModelScope.launch {
            appDB.addTask(name, systemPrompt, modelId)
        }
    }

    fun updateTask(newTask: Task) {
        viewModelScope.launch {
            appDB.updateTask(newTask)
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            appDB.deleteTask(taskId)
        }
    }
}
