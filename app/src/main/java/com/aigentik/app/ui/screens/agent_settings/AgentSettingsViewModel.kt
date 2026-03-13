package com.aigentik.app.ui.screens.agent_settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.aigentik.app.auth.AdminAuthManager
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ChannelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.koin.android.annotation.KoinViewModel

data class AgentSettingsUiState(
    val agentName: String = "",
    val ownerName: String = "",
    val adminNumber: String = "",
    val adminPassword: String = "",
    val adminPasswordConfirm: String = "",
    val smsEnabled: Boolean = true,
    val gvoiceEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val isPaused: Boolean = false,
    val googleEmail: String? = null,
    val gmailScopesGranted: Boolean = false,
    val hasPendingScopeResolution: Boolean = false,
    val statusMessage: String = "",
    val statusIsError: Boolean = false,
)

@KoinViewModel
class AgentSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AgentSettingsUiState())
    val uiState: StateFlow<AgentSettingsUiState> = _uiState

    init {
        load()
    }

    fun load() {
        val ctx = getApplication<Application>()
        _uiState.update {
            AgentSettingsUiState(
                agentName = AigentikSettings.agentName,
                ownerName = AigentikSettings.ownerName,
                adminNumber = AigentikSettings.adminNumber,
                smsEnabled = AigentikSettings.getChannelEnabled(ChannelManager.Channel.SMS.name),
                gvoiceEnabled = AigentikSettings.getChannelEnabled(ChannelManager.Channel.GVOICE.name),
                emailEnabled = AigentikSettings.getChannelEnabled(ChannelManager.Channel.EMAIL.name),
                isPaused = AigentikSettings.isPaused,
                googleEmail = GoogleAuthManager.getSignedInEmail(ctx),
                gmailScopesGranted = GoogleAuthManager.gmailScopesGranted,
                hasPendingScopeResolution = GoogleAuthManager.hasPendingScopeResolution(),
            )
        }
    }

    fun update(block: AgentSettingsUiState.() -> AgentSettingsUiState) {
        _uiState.update(block)
    }

    fun save(): Boolean {
        val s = _uiState.value
        if (s.ownerName.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Your name is required", statusIsError = true) }
            return false
        }
        if (s.adminNumber.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Phone number is required", statusIsError = true) }
            return false
        }
        if (s.adminPassword.isNotEmpty() || s.adminPasswordConfirm.isNotEmpty()) {
            if (s.adminPassword != s.adminPasswordConfirm) {
                _uiState.update { it.copy(statusMessage = "Passwords do not match", statusIsError = true) }
                return false
            }
            if (s.adminPassword.length < 4) {
                _uiState.update { it.copy(statusMessage = "Password must be at least 4 characters", statusIsError = true) }
                return false
            }
            AigentikSettings.adminPasswordHash = AdminAuthManager.hashPassword(s.adminPassword)
        }
        AigentikSettings.agentName = s.agentName.ifBlank { "Aigentik" }
        AigentikSettings.ownerName = s.ownerName
        AigentikSettings.adminNumber = s.adminNumber
        AigentikSettings.isPaused = s.isPaused
        if (s.smsEnabled) ChannelManager.enable(ChannelManager.Channel.SMS)
            else ChannelManager.disable(ChannelManager.Channel.SMS)
        if (s.gvoiceEnabled) ChannelManager.enable(ChannelManager.Channel.GVOICE)
            else ChannelManager.disable(ChannelManager.Channel.GVOICE)
        if (s.emailEnabled) ChannelManager.enable(ChannelManager.Channel.EMAIL)
            else ChannelManager.disable(ChannelManager.Channel.EMAIL)
        AigentikSettings.isConfigured = true
        _uiState.update { it.copy(adminPassword = "", adminPasswordConfirm = "", statusMessage = "Settings saved", statusIsError = false) }
        return true
    }

    fun onSignInSuccess(ctx: Context, account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        GoogleAuthManager.onSignInSuccess(ctx, account)
        AigentikSettings.isOAuthSignedIn = true
        account.email?.let { AigentikSettings.gmailAddress = it }
        _uiState.update {
            it.copy(
                googleEmail = account.email,
                statusMessage = "Signed in as ${account.email}",
                statusIsError = false,
            )
        }
    }

    fun onSignOut(ctx: Context) {
        GoogleAuthManager.signOut(ctx) {
            AigentikSettings.isOAuthSignedIn = false
            _uiState.update {
                it.copy(
                    googleEmail = null,
                    gmailScopesGranted = false,
                    hasPendingScopeResolution = false,
                    statusMessage = "Signed out",
                    statusIsError = false,
                )
            }
        }
    }

    fun onScopeConsentGranted() {
        GoogleAuthManager.onScopeConsentGranted()
        _uiState.update {
            it.copy(
                gmailScopesGranted = true,
                hasPendingScopeResolution = false,
                statusMessage = "Gmail permissions granted",
                statusIsError = false,
            )
        }
    }

    fun onScopeConsentDenied() {
        GoogleAuthManager.onScopeConsentDenied()
        _uiState.update {
            it.copy(
                statusMessage = "Gmail permissions denied — email features disabled",
                statusIsError = true,
            )
        }
    }

    fun refreshGoogleState(ctx: Context) {
        _uiState.update {
            it.copy(
                googleEmail = GoogleAuthManager.getSignedInEmail(ctx),
                gmailScopesGranted = GoogleAuthManager.gmailScopesGranted,
                hasPendingScopeResolution = GoogleAuthManager.hasPendingScopeResolution(),
            )
        }
    }

    fun setStatusMessage(msg: String, isError: Boolean = false) {
        _uiState.update { it.copy(statusMessage = msg, statusIsError = isError) }
    }
}
