package com.aigentik.app.ui.screens.agent_settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    viewModel: AgentSettingsViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onGrantGmailPerms: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // --- Identity ---
            SectionHeader("Identity")
            OutlinedTextField(
                value = uiState.agentName,
                onValueChange = { viewModel.update { copy(agentName = it) } },
                label = { Text("Agent Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.ownerName,
                onValueChange = { viewModel.update { copy(ownerName = it) } },
                label = { Text("Your Name (required)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.adminNumber,
                onValueChange = { viewModel.update { copy(adminNumber = it) } },
                label = { Text("Your Phone Number (required)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // --- Admin Password ---
            SectionHeader("Admin Password")
            Text(
                "Set a password to authenticate admin commands sent via SMS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var showPw by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = uiState.adminPassword,
                onValueChange = { viewModel.update { copy(adminPassword = it) } },
                label = { Text("New Password") },
                singleLine = true,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(
                            if (showPw) FeatherIcons.EyeOff else FeatherIcons.Eye,
                            contentDescription = if (showPw) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.adminPasswordConfirm,
                onValueChange = { viewModel.update { copy(adminPasswordConfirm = it) } },
                label = { Text("Confirm Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // --- Google Account ---
            SectionHeader("Google Account")
            if (uiState.googleEmail != null) {
                Text(
                    "Signed in as ${uiState.googleEmail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Gmail scope status
                val (scopeText, scopeColor) = when {
                    uiState.gmailScopesGranted ->
                        "Gmail: Permissions granted" to MaterialTheme.colorScheme.tertiary
                    uiState.hasPendingScopeResolution ->
                        "Gmail: Permissions needed" to MaterialTheme.colorScheme.error
                    else ->
                        "Gmail: Checking permissions..." to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(scopeText, style = MaterialTheme.typography.bodySmall, color = scopeColor)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.hasPendingScopeResolution || !uiState.gmailScopesGranted) {
                        Button(onClick = onGrantGmailPerms) {
                            Text("Grant Gmail Permissions")
                        }
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text("Sign Out")
                    }
                }
            } else {
                Text(
                    "Not signed in — Gmail and Google Voice features require Google sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSignIn) {
                    Text("Sign in with Google")
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // --- Channels ---
            SectionHeader("Message Channels")
            ToggleRow(
                label = "SMS / RCS",
                description = "Reply to SMS and RCS messages",
                checked = uiState.smsEnabled,
                onCheckedChange = { viewModel.update { copy(smsEnabled = it) } },
            )
            ToggleRow(
                label = "Google Voice",
                description = "Reply to Google Voice texts via Gmail",
                checked = uiState.gvoiceEnabled,
                onCheckedChange = { viewModel.update { copy(gvoiceEnabled = it) } },
            )
            ToggleRow(
                label = "Email",
                description = "Reply to Gmail messages",
                checked = uiState.emailEnabled,
                onCheckedChange = { viewModel.update { copy(emailEnabled = it) } },
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // --- Agent Control ---
            SectionHeader("Agent Control")
            ToggleRow(
                label = "Pause Agent",
                description = "Suspend all automatic replies",
                checked = uiState.isPaused,
                onCheckedChange = { viewModel.update { copy(isPaused = it) } },
            )

            Spacer(Modifier.height(16.dp))

            // --- Save ---
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Settings")
            }

            // Status message
            if (uiState.statusMessage.isNotEmpty()) {
                Text(
                    uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
