package com.aigentik.app.ui.screens.agent_settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.aigentik.app.ai.AgentLLMFacade
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.benchmark.BenchmarkRunner
import com.aigentik.app.benchmark.CorpusBuilder
import com.aigentik.app.benchmark.ExperimentConfig
import com.aigentik.app.benchmark.MetricsExporter
import com.aigentik.app.core.AgentNotificationManager
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.data.AppDB
import com.aigentik.app.email.GmailHistoryClient
import com.aigentik.app.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val appDB: AppDB by inject()
    private val settingsViewModel: AgentSettingsViewModel by koinViewModel()

    // State that increments on resume/auth events to force UI recomposition
    private var refreshTrigger by mutableStateOf(0)

    // Benchmark state (hoisted to Activity so it survives recomposition)
    private var benchmarkRunning by mutableStateOf(false)
    private var benchmarkProgress by mutableIntStateOf(0)
    private var benchmarkTotal by mutableIntStateOf(0)
    private var benchmarkResultText by mutableStateOf("")
    private var benchmarkLastExportPath by mutableStateOf("")

    // Corpus state
    private var corpusGenerating by mutableStateOf(false)
    private var corpusTaskCount by mutableIntStateOf(-1)   // -1 = not checked yet

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
        refreshCorpusStatus()
        setContent {
            SmolLMAndroidTheme {
                AgentSettingsScreen(
                    viewModel       = settingsViewModel,
                    refreshTrigger  = refreshTrigger,
                    onSignInClick   = {
                        signInLauncher.launch(
                            GoogleAuthManager.buildSignInClient(this).signInIntent
                        )
                    },
                    onGrantGmailClick = {
                        val pending = GoogleAuthManager.pendingScopeIntent
                        if (pending != null) {
                            scopeConsentLauncher.launch(pending)
                        } else {
                            lifecycleScope.launch {
                                GoogleAuthManager.getFreshToken(applicationContext)
                                refreshTrigger++
                            }
                        }
                    },
                    onSignOutClick  = {
                        // Sign-out confirmation is shown inside the composable (L-3)
                        settingsViewModel.onSignOut(this)
                        refreshTrigger++
                    },
                    onBackClick     = { finish() },
                    benchmarkRunning    = benchmarkRunning,
                    benchmarkProgress   = benchmarkProgress,
                    benchmarkTotal      = benchmarkTotal,
                    benchmarkResultText = benchmarkResultText,
                    benchmarkExportPath = benchmarkLastExportPath,
                    onRunBenchmark  = { corpusPath, experimentId ->
                        runBenchmark(corpusPath, experimentId)
                    },
                    onShareResults  = { shareBenchmarkResults(it) },
                    corpusGenerating    = corpusGenerating,
                    corpusTaskCount     = corpusTaskCount,
                    defaultCorpusPath   = CorpusBuilder.getDefaultCorpusFile(applicationContext).absolutePath,
                    onGenerateCorpus    = { onPathReady -> generateCorpus(onPathReady) },
                )
            }
        }
    }

    private fun runBenchmark(corpusPath: String, experimentId: String) {
        if (benchmarkRunning) return
        benchmarkRunning    = true
        benchmarkProgress   = 0
        benchmarkTotal      = 0
        benchmarkResultText = "Starting…"
        benchmarkLastExportPath = ""

        val config = ExperimentConfig(
            experimentId = experimentId,
            corpusPath   = corpusPath,
            description  = "Run from settings UI",
        )
        lifecycleScope.launch {
            try {
                val dao    = appDB.taskMetricDao()
                val runner = BenchmarkRunner(
                    context    = applicationContext,
                    config     = config,
                    dao        = dao,
                    onProgress = { done, total ->
                        benchmarkProgress = done
                        benchmarkTotal    = total
                    },
                )
                val completed = runner.run()
                val metrics   = dao.getForExperiment(experimentId)
                val outPath   = MetricsExporter.export(applicationContext, config, metrics)
                benchmarkLastExportPath = outPath ?: ""
                benchmarkResultText = if (outPath != null)
                    "$completed tasks complete. Results saved."
                else
                    "$completed tasks complete. Export failed."
                AgentNotificationManager.postBenchmark(
                    "[$experimentId] $benchmarkResultText"
                )
            } catch (e: Exception) {
                benchmarkResultText = "Error: ${e.message?.take(120)}"
                Log.e(TAG, "Benchmark failed", e)
            } finally {
                benchmarkRunning = false
            }
        }
    }

    private fun refreshCorpusStatus() {
        // File I/O on IO thread — avoids blocking main thread on cold launch
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val file = CorpusBuilder.getDefaultCorpusFile(applicationContext)
                if (file.exists() && file.length() > 1024L)
                    file.bufferedReader().lineSequence()
                        .count { it.isNotBlank() && !it.startsWith("//") }
                else -1
            }
            corpusTaskCount = count
        }
    }

    private fun generateCorpus(onPathReady: (String) -> Unit) {
        if (corpusGenerating) return
        corpusGenerating = true
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    CorpusBuilder.generate(applicationContext)
                }
                val count = withContext(Dispatchers.IO) {
                    file.bufferedReader().lineSequence()
                        .count { it.isNotBlank() && !it.startsWith("//") }
                }
                corpusTaskCount = count
                onPathReady(file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Corpus generation failed: ${e.message}")
            } finally {
                corpusGenerating = false
            }
        }
    }

    private fun shareBenchmarkResults(path: String) {
        try {
            val csv = File(File(path), "metrics.csv")
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", csv)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share benchmark results"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSettingsScreen(
    viewModel: AgentSettingsViewModel,
    refreshTrigger: Int,
    onSignInClick: () -> Unit,
    onGrantGmailClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onBackClick: () -> Unit,
    benchmarkRunning: Boolean = false,
    benchmarkProgress: Int = 0,
    benchmarkTotal: Int = 0,
    benchmarkResultText: String = "",
    benchmarkExportPath: String = "",
    onRunBenchmark: (corpusPath: String, experimentId: String) -> Unit = { _, _ -> },
    onShareResults: (path: String) -> Unit = {},
    corpusGenerating: Boolean = false,
    corpusTaskCount: Int = -1,
    defaultCorpusPath: String = "",
    onGenerateCorpus: ((String) -> Unit) -> Unit = {},
) {
    // ViewModel state — auth and channel toggles re-read on refreshTrigger
    val uiState by viewModel.uiState.collectAsState()

    // Re-load from settings when resumed (refreshTrigger bumps on onResume)
    remember(refreshTrigger) { viewModel.load() }

    // Auth state directly from GoogleAuthManager (in-memory, safe on main thread)
    val gmailGranted = GoogleAuthManager.gmailScopesGranted
    val hasPendingScope = GoogleAuthManager.hasPendingScopeResolution()
    val lastTokenError = GoogleAuthManager.lastTokenError

    // Sign-out confirmation dialog state (L-3)
    var showSignOutDialog by remember { mutableStateOf(false) }

    // Benchmark local fields
    var corpusPath by rememberSaveable { mutableStateOf(defaultCorpusPath) }
    val defaultExpId = remember {
        "exp_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
    var experimentId by rememberSaveable { mutableStateOf(defaultExpId) }

    // Status (read once per composition)
    val aiStatus = AgentLLMFacade.getStateLabel()
    val contactCount = ContactEngine.getCount()
    val gmailStatus = when {
        gmailGranted     -> "Ready"
        hasPendingScope  -> "Needs permissions"
        lastTokenError != null -> "Error: ${lastTokenError.take(40)}"
        else             -> "Not signed in"
    }

    // Sign-out confirmation dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title   = { Text("Sign Out?") },
            text    = { Text("This will disable Gmail and Google Voice auto-reply until you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    onSignOutClick()
                }) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
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
                            onClick = { showSignOutDialog = true },
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
                            onClick = { showSignOutDialog = true },
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
                        "Tap Save to apply changes. Service restart required for name/number changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.agentName,
                        onValueChange = { viewModel.update { copy(agentName = it) } },
                        label = { Text("Agent Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.ownerName,
                        onValueChange = { viewModel.update { copy(ownerName = it) } },
                        label = { Text("Your Name") },
                        isError = uiState.ownerName.isBlank(),
                        supportingText = if (uiState.ownerName.isBlank()) {
                            { Text("Required", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.adminNumber,
                        onValueChange = { viewModel.update { copy(adminNumber = it) } },
                        label = { Text("Your Phone Number") },
                        supportingText = { Text("Messages from this number are treated as admin commands") },
                        isError = uiState.adminNumber.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.adminPassword,
                        onValueChange = { viewModel.update { copy(adminPassword = it) } },
                        label = { Text("Admin Password") },
                        supportingText = { Text("Used for remote SMS admin commands (leave blank to keep existing)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (uiState.adminPassword.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.adminPasswordConfirm,
                            onValueChange = { viewModel.update { copy(adminPasswordConfirm = it) } },
                            label = { Text("Confirm Password") },
                            isError = uiState.adminPassword != uiState.adminPasswordConfirm,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // Show status/error message from ViewModel save()
                    if (uiState.statusMessage.isNotBlank()) {
                        Text(
                            uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.statusIsError)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { viewModel.save() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.ownerName.isNotBlank() && uiState.adminNumber.isNotBlank(),
                    ) {
                        Text("Save Settings")
                    }
                }
            }

            // ─── Channels ────────────────────────────────────────────────────
            item {
                SectionCard(title = "Channels") {
                    ChannelRow(
                        label = "SMS / RCS",
                        description = "Auto-reply to text messages",
                        enabled = uiState.smsEnabled,
                        onToggle = {
                            viewModel.update { copy(smsEnabled = it) }
                            if (it) ChannelManager.enable(ChannelManager.Channel.SMS)
                            else    ChannelManager.disable(ChannelManager.Channel.SMS)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ChannelRow(
                        label = "Email",
                        description = "Auto-reply to Gmail messages",
                        enabled = uiState.emailEnabled,
                        onToggle = {
                            viewModel.update { copy(emailEnabled = it) }
                            if (it) ChannelManager.enable(ChannelManager.Channel.EMAIL)
                            else    ChannelManager.disable(ChannelManager.Channel.EMAIL)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ChannelRow(
                        label = "Google Voice",
                        description = "Auto-reply to GVoice texts via Gmail",
                        enabled = uiState.gvoiceEnabled,
                        onToggle = {
                            viewModel.update { copy(gvoiceEnabled = it) }
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
                    StatusRow("Agent Folder", if (AgentNotificationManager.getFolderId() != -1L) "Ready" else "Pending restart")
                }
            }

            // ─── Agent Pipeline Benchmark ─────────────────────────────────────
            item {
                SectionCard(title = "Agent Pipeline Benchmark") {
                    Text(
                        "Run end-to-end latency, battery, and thermal benchmarks against a JSONL task corpus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    // Corpus status row
                    val corpusStatus = when {
                        corpusGenerating    -> "Generating…"
                        corpusTaskCount < 0 -> "Not generated"
                        else                -> "$corpusTaskCount tasks ready"
                    }
                    StatusRow("Corpus", corpusStatus)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            onGenerateCorpus { path -> corpusPath = path }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !corpusGenerating && !benchmarkRunning,
                    ) {
                        Text(
                            if (corpusGenerating) "Generating…"
                            else if (corpusTaskCount >= 0) "Regenerate Corpus (500 tasks)"
                            else "Generate Corpus (500 tasks)"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = corpusPath,
                        onValueChange = { corpusPath = it },
                        label = { Text("Corpus file path") },
                        placeholder = { Text(defaultCorpusPath.ifBlank { "/sdcard/Download/corpus.jsonl" }) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !benchmarkRunning,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = experimentId,
                        onValueChange = { experimentId = it },
                        label = { Text("Experiment ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !benchmarkRunning,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (benchmarkRunning) {
                        val progress = if (benchmarkTotal > 0)
                            benchmarkProgress.toFloat() / benchmarkTotal else 0f
                        Text(
                            "Running… $benchmarkProgress / $benchmarkTotal tasks",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Button(
                            onClick = { onRunBenchmark(corpusPath.trim(), experimentId.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = corpusPath.isNotBlank() && experimentId.isNotBlank(),
                        ) {
                            Text("Run Benchmark")
                        }
                    }

                    if (benchmarkResultText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            benchmarkResultText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (benchmarkResultText.startsWith("Error"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (benchmarkExportPath.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onShareResults(benchmarkExportPath) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Share metrics.csv")
                        }
                    }
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
