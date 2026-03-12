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

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import com.aigentik.app.R
import com.aigentik.app.data.LLMModel
import com.aigentik.app.data.Task
import com.aigentik.app.ui.components.AppAlertDialog
import com.aigentik.app.ui.components.AppBarTitleText
import com.aigentik.app.ui.components.TasksList
import com.aigentik.app.ui.components.createAlertDialog
import com.aigentik.app.ui.preview.dummyLLMModels
import com.aigentik.app.ui.preview.dummyTasksList
import com.aigentik.app.ui.theme.SmolLMAndroidTheme
import org.koin.android.ext.android.inject

class ManageTasksActivity : ComponentActivity() {
    private val viewModel: TasksViewModel by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Box(modifier = Modifier.safeDrawingPadding()) {
                val tasks by viewModel.tasksState.collectAsState()
                val models by viewModel.appDB.getModels().collectAsState(emptyList())
                TasksActivityScreenUI(
                    tasks = tasks,
                    availableModelsList = models,
                    onUpdateTask = viewModel::updateTask,
                    onAddTask = viewModel::addTask,
                    onDeleteTask = viewModel::deleteTask,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewTasksActivityScreenUI() {
    TasksActivityScreenUI(
        tasks = dummyTasksList,
        availableModelsList = dummyLLMModels,
        onUpdateTask = {},
        onAddTask = { _, _, _ -> },
        onDeleteTask = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksActivityScreenUI(
    tasks: List<Task>,
    availableModelsList: List<LLMModel>,
    onAddTask: (String, String, Long) -> Unit,
    onUpdateTask: (Task) -> Unit,
    onDeleteTask: (Long) -> Unit,
) {
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var showEditTaskDialog by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    val context = LocalContext.current
    SmolLMAndroidTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        AppBarTitleText(text = stringResource(R.string.tasks_manage_tasks_title))
                    },
                    actions = {
                        IconButton(onClick = { showCreateTaskDialog = true }) {
                            Icon(FeatherIcons.Plus, contentDescription = "Add New Task")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { (context as ManageTasksActivity).finish() }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Navigate Back")
                        }
                    },
                )
            }
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxSize()
                        .padding(paddingValues)
            ) {
                Text(
                    text = stringResource(R.string.tasks_manage_tasks_desc),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TasksList(
                    tasks,
                    onTaskSelected = { /* Not applicable as enableTaskClick is set to `false` */ },
                    onUpdateTaskClick = { task -> onUpdateTask(task) },
                    onEditTaskClick = { task ->
                        selectedTask = task
                        showEditTaskDialog = true
                    },
                    onDeleteTaskClick = { task ->
                        createAlertDialog(
                            dialogTitle = context.getString(R.string.dialog_delete_task_title),
                            dialogText = "Are you sure you want to delete task '${task.name}'?",
                            dialogPositiveButtonText =
                                context.getString(R.string.dialog_pos_delete),
                            dialogNegativeButtonText =
                                context.getString(R.string.dialog_neg_cancel),
                            onPositiveButtonClick = {
                                onDeleteTask(task.id)
                                Toast.makeText(
                                        context,
                                        "Task '${task.name}' deleted",
                                        Toast.LENGTH_LONG,
                                )
                                    .show()
                            },
                            onNegativeButtonClick = {},
                        )
                    },
                    enableTaskClick = false,
                    showTaskOptions = true,
                )
                if (showCreateTaskDialog) {
                    CreateTaskDialog(
                        availableModelsList = availableModelsList,
                        onDismiss = { showCreateTaskDialog = false },
                        onAddTask = onAddTask,
                    )
                }
                if (showEditTaskDialog && selectedTask != null) {
                    EditTaskDialog(
                        selectedTask = selectedTask!!,
                        availableModelsList = availableModelsList,
                        onDismiss = { showEditTaskDialog = false },
                        onUpdateTask = onUpdateTask,
                    )
                }
                AppAlertDialog()
            }
        }
    }
}
