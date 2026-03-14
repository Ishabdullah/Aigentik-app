package com.aigentik.app.auth

import android.util.Log
import com.aigentik.app.core.AigentikSettings
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// AdminAuthManager v2.0 — PBKDF2 password hashing + destructive command confirmation
//
// v2.0 changes:
//   - hashPassword() now uses PBKDF2WithHmacSHA256 (100k iterations) with a random 16-byte salt.
//     Stored as "saltHex:hashHex". Migration path: if stored hash has no ':', it is a legacy
//     SHA-256 hash — authenticate() accepts it to avoid locking out existing users.
//   - authenticate() auth failure log no longer exposes field-level breakdown (was logging
//     name=$nameMatch password=$passwordMatch — timing side-channel for password guessing).
//   - Added destructive command confirmation state machine (H-7 + M-9):
//       setPendingDestructive(channelKey, command) — arms confirmation
//       consumePendingDestructive(channelKey, confirmed) — matches and clears pending command
//
// Admin login format (SMS, GVoice email, Gmail):
//   Admin: Ish
//   Password: yourpassword
//   <command on next line(s)>
//
// Session management:
//   - Remote session expires after 30 min of inactivity
//   - Each command resets the timer
//   - Destructive actions always require confirmation via "confirm: <original command>"
object AdminAuthManager {

    private const val TAG = "AdminAuthManager"
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS   = 256
    private const val SALT_BYTES        = 16

    // Active remote sessions — key = channel identifier (phone/email)
    // ConcurrentHashMap: authenticate() and hasActiveSession() called from multiple IO coroutines
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Pending destructive commands awaiting confirmation — key = channelKey
    private val pendingDestructiveCommands = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Destructive action keywords — always require confirmation
    private val DESTRUCTIVE_KEYWORDS = listOf(
        "delete", "remove", "erase", "wipe", "clear",
        "spam", "unsubscribe", "bulk", "all emails", "all contacts",
        "send to all", "mass text", "broadcast"
    )

    // Parse admin credentials from message body
    data class AdminCredentials(val username: String, val password: String, val command: String)

    fun parseAdminMessage(body: String): AdminCredentials? {
        val lines = body.trim().lines().map { it.trim() }

        val adminLine = lines.firstOrNull {
            it.lowercase().startsWith("admin:") || it.lowercase().startsWith("admin :")
        }
        val passwordLine = lines.firstOrNull {
            it.lowercase().startsWith("password:") || it.lowercase().startsWith("password :")
        }

        if (adminLine == null || passwordLine == null) return null

        val username = adminLine.substringAfter(":").trim()
        val password = passwordLine.substringAfter(":").trim()

        val adminLineIdx    = lines.indexOf(adminLine)
        val passwordLineIdx = lines.indexOf(passwordLine)
        val commandStartIdx = maxOf(adminLineIdx, passwordLineIdx) + 1
        val command = lines.drop(commandStartIdx).joinToString("\n").trim()

        if (username.isBlank() || password.isBlank()) return null

        return AdminCredentials(username, password, command)
    }

    // Authenticate admin credentials.
    // Returns true if username matches ownerName and password matches stored hash
    // (PBKDF2 format "salt:hash" or legacy SHA-256 for migration).
    fun authenticate(credentials: AdminCredentials, channelKey: String): Boolean {
        val ownerName  = AigentikSettings.ownerName
        val storedHash = AigentikSettings.adminPasswordHash

        if (storedHash.isBlank()) {
            Log.w(TAG, "No admin password set — remote admin disabled")
            return false
        }

        val nameMatch     = credentials.username.trim().equals(ownerName.trim(), ignoreCase = true)
        val passwordMatch = verifyPassword(credentials.password, storedHash)

        return if (nameMatch && passwordMatch) {
            activeSessions[channelKey] = System.currentTimeMillis()
            Log.i(TAG, "Admin authenticated on channel: $channelKey")
            true
        } else {
            // Single log line — no field breakdown to avoid timing/info leakage
            Log.w(TAG, "Admin auth failed for channel: $channelKey")
            false
        }
    }

    // Check if channel has an active session
    fun hasActiveSession(channelKey: String): Boolean {
        val lastAuth = activeSessions[channelKey] ?: return false
        val elapsed  = System.currentTimeMillis() - lastAuth
        return if (elapsed < SESSION_TIMEOUT_MS) {
            activeSessions[channelKey] = System.currentTimeMillis()
            true
        } else {
            activeSessions.remove(channelKey)
            Log.i(TAG, "Session expired for: $channelKey")
            false
        }
    }

    // Check if command is destructive and needs confirmation
    fun isDestructiveCommand(command: String): Boolean {
        val lower = command.lowercase()
        return DESTRUCTIVE_KEYWORDS.any { lower.contains(it) }
    }

    // ─── Destructive command confirmation state machine ────────────────────────

    /** Store a pending destructive command awaiting confirmation. */
    fun setPendingDestructive(channelKey: String, command: String) {
        pendingDestructiveCommands[channelKey] = command
        Log.i(TAG, "Pending destructive command armed for $channelKey: ${command.take(60)}")
    }

    /**
     * Attempt to match [confirmedCommand] against the stored pending command for [channelKey].
     * If it matches, clears the pending entry and returns the stored command (ready to execute).
     * If it does not match (or there is no pending command), returns null.
     */
    fun consumePendingDestructive(channelKey: String, confirmedCommand: String): String? {
        val pending = pendingDestructiveCommands[channelKey] ?: return null
        return if (confirmedCommand.trim().equals(pending.trim(), ignoreCase = true)) {
            pendingDestructiveCommands.remove(channelKey)
            Log.i(TAG, "Destructive command confirmed for $channelKey: ${pending.take(60)}")
            pending
        } else {
            Log.w(TAG, "Confirm mismatch for $channelKey — pending not consumed")
            null
        }
    }

    /** Cancel any pending destructive command for this channel (e.g. on session expiry). */
    fun clearPendingDestructive(channelKey: String) {
        pendingDestructiveCommands.remove(channelKey)
    }

    // ─── Password hashing ─────────────────────────────────────────────────────

    /**
     * Hash [password] using PBKDF2WithHmacSHA256 (100k iterations, 256-bit key).
     * Returns "saltHex:hashHex". If [existingSalt] is provided (for verification), uses it
     * instead of generating a new random salt.
     */
    fun hashPassword(password: String, existingSalt: ByteArray? = null): String {
        val salt = existingSalt ?: ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val spec    = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash    = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return "${salt.toHex()}:${hash.toHex()}"
    }

    /**
     * Verify [password] against [storedHash].
     * Supports both PBKDF2 format ("saltHex:hashHex") and legacy SHA-256 (migration path).
     */
    fun verifyPassword(password: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return false
        return if (storedHash.contains(':')) {
            // PBKDF2 format — extract salt and rehash with same salt
            val parts   = storedHash.split(':', limit = 2)
            val salt    = parts[0].fromHex() ?: return false
            hashPassword(password, salt) == storedHash
        } else {
            // Legacy SHA-256 format — accept during migration so existing users aren't locked out
            val legacy = MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .toHex()
            legacy == storedHash
        }
    }

    fun clearSession(channelKey: String) {
        activeSessions.remove(channelKey)
        clearPendingDestructive(channelKey)
    }

    fun clearAllSessions() {
        activeSessions.clear()
        pendingDestructiveCommands.clear()
        Log.i(TAG, "All admin sessions cleared")
    }

    // ─── Hex helpers ──────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? = try {
        ByteArray(length / 2) { i -> Integer.parseInt(substring(i * 2, i * 2 + 2), 16).toByte() }
    } catch (e: Exception) {
        Log.w(TAG, "Invalid hex string: ${e.message}")
        null
    }
}
