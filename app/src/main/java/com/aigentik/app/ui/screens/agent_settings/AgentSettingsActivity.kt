package com.aigentik.app.ui.screens.agent_settings

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.aigentik.app.ai.AgentLLMFacade
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.email.GmailHistoryClient
import com.aigentik.app.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.launch

// AgentSettingsActivity — Aigentik agent configuration + Gmail OAuth
//
// Launched from the ⋮ menu in ChatActivity ("Aigentik Settings").
//
// OAuth flow:
//   1. "Sign In with Google" → signInLauncher → GoogleAuthManager.onSignInSuccess()
//      → immediately calls getFreshToken() which fires scopeResolutionListener if scopes needed
//   2. scopeResolutionListener (set in onResume) → scopeConsentLauncher.launch(pendingIntent)
//      → user grants Gmail permissions in system dialog
//   3. scopeConsentLauncher result → GoogleAuthManager.onScopeConsentGranted()
//      → GmailHistoryClient.primeHistoryId() (establishes baseline for delta fetch)
//
// "Grant Gmail Permissions" button is shown whenever Gmail scopes are not yet granted.
// Tapping it either launches the stored pendingScopeIntent directly, or calls getFreshToken()
// which re-triggers the UserRecoverableAuthException → scopeResolutionListener path.
class AgentSettingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AgentSettingsActivity"
    }

    // State that increments on resume/auth events to force UI recomposition
    private var refreshTrigger by mutableStateOf(0)

    // Google Sign-In launcher
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                GoogleAuthManager.onSignInSuccess(this, account)
                Log.i(TAG, "Sign-in success: ${account.email}")
                // Try to get token immediately — triggers consent dialog if Gmail scopes not yet granted
                lifecycleScope.launch {
                    GoogleAuthManager.getFreshToken(applicationContext)
                    refreshTrigger++
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed: code=${e.statusCode}")
            }
        }
        refreshTrigger++
    }

    // Gmail scope consent launcher (UserRecoverableAuthException resolution)
    private val scopeConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            GoogleAuthManager.onScopeConsentGranted()
            Log.i(TAG, "Gmail scope consent granted")
            // Establish historyId baseline now that we have Gmail access
            lifecycleScope.launch {
                val primeResult = GmailHistoryClient.primeHistoryId(applicationContext)
                Log.i(TAG, "historyId primed after consent: $primeResult")
            }
        } else {
            GoogleAuthManager.onScopeConsentDenied()
            Log.w(TAG, "Gmail scope consent denied or cancelled")
        }
        refreshTrigger++
    }

    override fun onResume() {
        super.onResume()
        // Set scope resolution listener BEFORE any getFreshToken() call.
        // When getFreshToken() throws UserRecoverableAuthException (Gmail scopes needed),
        // it invokes this listener on the IO thread — runOnUiThread ensures the launcher
        // is called from the main thread as required by ActivityResultLauncher.
        GoogleAuthManager.scopeResolutionListener = { intent ->
            runOnUiThread {
                Log.i(TAG, "Scope resolution needed — launching consent dialog")
                scopeConsentLauncher.launch(intent)
            }
        }
        // Re-prime historyId on resume in case the user just completed OAuth elsewhere
        if (GoogleAuthManager.isSignedIn(this)) {
            lifecycleScope.launch {
                GmailHistoryClient.primeHistoryId(applicationContext)
            }
        }
        refreshTrigger++
    }

    override fun onPause() {
        super.onPause()
        // Always clear listener in onPause to avoid leaking Activity reference.
        // If this Activity goes into the background (e.g. while consent dialog is shown),
        // the listener is re-set in onResume when we come back.
        GoogleAuthManager.scopeResolutionListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmolLMAndroidTheme {
                AgentSettingsScreen(
                    refreshTrigger = refreshTrigger,
                    onSignInClick = {
                        signInLauncher.launch(
                            GoogleAuthManager.buildSignInClient(this).signInIntent
                        )
                    },
                    onGrantGmailClick = {
                        val pending = GoogleAuthManager.pendingScopeIntent
                        if (pending != null) {
                            // Already have the consent intent — launch it directly
                            scopeConsentLauncher.launch(pending)
                        } else {
                            // No stored intent yet — call getFreshToken() which will
                            // throw UserRecoverableAuthException → scopeResolutionListener fires
                            lifecycleScope.launch {
                                GoogleAuthManager.getFreshToken(applicationContext)
                                refreshTrigger++
                            }
                        }
                    },
                    onSignOutClick = {
                        GoogleAuthManager.signOut(this) { refreshTrigger++ }
                    },
                    onBackClick = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSettingsScreen(
    refreshTrigger: Int,
    onSignInClick: () -> Unit,
    onGrantGmailClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    // Auth state — re-read on every refreshTrigger change
    val isSignedIn by remember(refreshTrigger) {
        mutableStateOf(GoogleAuthManager.gmailScopesGranted.let {
            // isSignedIn can't use context here, use gmailScopesGranted as proxy
            true  // placeholder — set below
        })
    }
    // Read directly from GoogleAuthManager (in-memory, safe on main thread)
    val gmailGranted = GoogleAuthManager.gmailScopesGranted
    val hasPendingScope = GoogleAuthManager.hasPendingScopeResolution()
    val lastTokenError = GoogleAuthManager.lastTokenError

    // Settings fields — auto-save to SharedPreferences on change
    var agentName by rememberSaveable(refreshTrigger) { mutableStateOf(AigentikSettings.agentName) }
    var ownerName by rememberSaveable(refreshTrigger) { mutableStateOf(AigentikSettings.ownerName) }
    var adminNumber by rememberSaveable(refreshTrigger) { mutableStateOf(AigentikSettings.adminNumber) }

    // Channel toggles — re-read on refreshTrigger
    var smsEnabled by remember(refreshTrigger) {
        mutableStateOf(ChannelManager.isEnabled(ChannelManager.Channel.SMS))
    }
    var emailEnabled by remember(refreshTrigger) {
        mutableStateOf(ChannelManager.isEnabled(ChannelManager.Channel.EMAIL))
    }
    var gvoiceEnabled by remember(refreshTrigger) {
        mutableStateOf(ChannelManager.isEnabled(ChannelManager.Channel.GVOICE))
    }

    // Status (read once per composition)
    val aiStatus = AgentLLMFacade.getStateLabel()
    val contactCount = ContactEngine.getCount()
    val gmailStatus = when {
        gmailGranted     -> "Ready"
        hasPendingScope  -> "Needs permissions"
        lastTokenError != null -> "Error: ${lastTokenError.take(40)}"
        else             -> "Not signed in"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aigentik Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ─── Google Account ─────────────────────────────────────────────
            item {
                SectionCard(title = "Google Account") {
                    if (gmailGranted) {
                        // Fully signed in with Gmail scopes
                        val email = AigentikSettings.gmailAddress
                        if (email.isNotBlank()) {
                            Text(
                                "Signed in as: $email",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "Gmail: permissions granted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onSignOutClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Sign Out")
                        }
                    } else if (hasPendingScope) {
                        // Signed in but Gmail scopes not yet granted
                        val email = AigentikSettings.gmailAddress
                        if (email.isNotBlank()) {
                            Text("Signed in as: $email", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "Gmail permissions required for email and Google Voice features.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (lastTokenError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                lastTokenError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onGrantGmailClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant Gmail Permissions")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onSignOutClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Sign Out")
                        }
                    } else {
                        // Not signed in
                        Text(
                            "Sign in with Google to enable Gmail and Google Voice auto-reply.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (lastTokenError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                lastTokenError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onSignInClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign In with Google")
                        }
                    }
                }
            }

            // ─── Agent Configuration ─────────────────────────────────────────
            item {
                SectionCard(title = "Agent Configuration") {
                    Text(
                        "Changes take effect on next service restart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = agentName,
                        onValueChange = {
                            agentName = it
                            AigentikSettings.agentName = it
                        },
                        label = { Text("Agent Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ownerName,
                        onValueChange = {
                            ownerName = it
                            AigentikSettings.ownerName = it
                        },
                        label = { Text("Your Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = adminNumber,
                        onValueChange = {
                            adminNumber = it
                            AigentikSettings.adminNumber = it
                        },
                        label = { Text("Your Phone Number") },
                        supportingText = { Text("Messages from this number are treated as admin commands") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            }

            // ─── Channels ────────────────────────────────────────────────────
            item {
                SectionCard(title = "Channels") {
                    ChannelRow(
                        label = "SMS / RCS",
                        description = "Auto-reply to text messages",
                        enabled = smsEnabled,
                        onToggle = {
                            smsEnabled = it
                            if (it) ChannelManager.enable(ChannelManager.Channel.SMS)
                            else    ChannelManager.disable(ChannelManager.Channel.SMS)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ChannelRow(
                        label = "Email",
                        description = "Auto-reply to Gmail messages",
                        enabled = emailEnabled,
                        onToggle = {
                            emailEnabled = it
                            if (it) ChannelManager.enable(ChannelManager.Channel.EMAIL)
                            else    ChannelManager.disable(ChannelManager.Channel.EMAIL)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ChannelRow(
                        label = "Google Voice",
                        description = "Auto-reply to GVoice texts via Gmail",
                        enabled = gvoiceEnabled,
                        onToggle = {
                            gvoiceEnabled = it
                            if (it) ChannelManager.enable(ChannelManager.Channel.GVOICE)
                            else    ChannelManager.disable(ChannelManager.Channel.GVOICE)
                        }
                    )
                }
            }

            // ─── Status ───────────────────────────────────────────────────────
            item {
                SectionCard(title = "Status") {
                    StatusRow("AI Model", aiStatus)
                    StatusRow("Contacts", "$contactCount loaded")
                    StatusRow("Gmail", gmailStatus)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ChannelRow(
    label: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
