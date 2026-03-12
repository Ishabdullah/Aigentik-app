package com.aigentik.app.core

import android.content.Context
import android.util.Log
import com.aigentik.app.ai.AgentLLMFacade
import com.aigentik.app.auth.AdminAuthManager
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailApiClient
import com.aigentik.app.sms.NotificationReplyRouter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// MessageEngine v2.0 — Stage 2: SMS/RCS + Gmail + Google Voice
// v2.0: Email reply path enabled via EmailRouter (Stage 2).
//   - Message and Message.Channel are now top-level (core/Message.kt).
//   - EMAIL and GVOICE channels route replies via EmailRouter.routeReply().
//   - Admin check_email command fetches unread count from GmailApiClient.
//   - Admin send_email command routes via EmailRouter.sendEmailDirect().
//
// v1.0: SMS/RCS only — email actions stubbed.
//
// Key design choices:
//   - messageMutex: serializes ALL message handlers — only one runs at a time.
//     Prevents concurrent LLM context churn (e.g. 10 email coroutines flooding
//     the same 128MB KV cache simultaneously → memory fragmentation/crash).
//   - Admin auth via AdminAuthManager: SMS admin login with session management.
//   - Public messages: ContactEngine for sender lookup, AgentLLMFacade for reply.
//   - Reply routing: NotificationReplyRouter for SMS/RCS, EmailRouter for email.
//   - PARTIAL_WAKE_LOCK acquired per-handler (passed in from AigentikService).
object MessageEngine {

    private const val TAG = "MessageEngine"

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Unhandled coroutine exception: ${e.message}", e)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Serialises ALL message handlers — only one runs at a time.
    private val messageMutex = Mutex()

    private var adminNumber  = ""
    private var ownerName    = "Owner"
    private var agentName    = "Aigentik"
    private var ownerNotifier: ((String) -> Unit)? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // chatNotifier posts messages into the chat UI so they appear in history
    @Volatile var chatNotifier: ((String) -> Unit)? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun configure(
        context: Context,
        adminNumber: String,
        ownerName: String,
        agentName: String,
        ownerNotifier: (String) -> Unit,
        wakeLock: android.os.PowerManager.WakeLock? = null
    ) {
        this.appContext    = context.applicationContext
        this.adminNumber   = PhoneNormalizer.toE164(adminNumber)
        this.ownerName     = ownerName
        this.agentName     = agentName
        this.ownerNotifier = ownerNotifier
        this.wakeLock      = wakeLock
        AgentLLMFacade.configure(agentName, ownerName)
        Log.i(TAG, "$agentName MessageEngine configured (Stage 2 — SMS/RCS + Email)")
    }

    // Prime context from ChatActivity.onCreate() before AigentikService completes startup
    fun initContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.i(TAG, "appContext primed from ChatActivity")
        }
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message from ${message.sender} via ${message.channel}")
        if (AigentikSettings.isPaused) {
            Log.i(TAG, "System paused — ignoring")
            return
        }

        // Admin check — CHAT is always trusted; remote channels need session/login
        val isAdminChannel = message.channel == Message.Channel.CHAT

        if (!isAdminChannel) {
            // Check for admin login format: Admin: Ish\nPassword: xxxx\n<command>
            val creds = AdminAuthManager.parseAdminMessage(message.body)
            if (creds != null) {
                if (AdminAuthManager.authenticate(creds, message.sender)) {
                    if (creds.command.isNotBlank()) {
                        scope.launch {
                            val authedMessage = message.copy(body = creds.command)
                            handleAdminCommand(authedMessage)
                        }
                    } else {
                        val ack = "Admin authenticated. Session active for 30 minutes."
                        notify(ack)
                        replyToSender(message, ack)
                    }
                } else {
                    val fail = "Authentication failed. Check username and password."
                    notify(fail)
                    replyToSender(message, fail)
                }
                return
            }
        }

        val isAdmin = isAdminChannel ||
            AdminAuthManager.hasActiveSession(message.sender) ||
            (adminNumber.isNotBlank() &&
             message.sender.filter { it.isDigit() }.takeLast(10) ==
             adminNumber.filter { it.isDigit() }.takeLast(10))

        scope.launch {
            // Acquire wake lock for the duration of inference so Samsung doesn't
            // throttle CPU in background (30s → 5+ minutes without it).
            val wl = wakeLock
            wl?.acquire(10 * 60 * 1000L)
            try {
                messageMutex.withLock {
                    if (isAdmin) handleAdminCommand(message)
                    else handlePublicMessage(message)
                }
            } finally {
                if (wl?.isHeld == true) wl.release()
            }
        }
    }

    // ─── Admin command handler ────────────────────────────────────────────────

    private suspend fun handleAdminCommand(message: Message) {
        Log.i(TAG, "handleAdminCommand: channel=${message.channel} body='${message.body.take(80)}'")

        val input = message.body.trim()
        val lower = input.lowercase()

        // Channel toggle — instant, no LLM
        val channelToggle = ChannelManager.parseToggleCommand(lower)
        if (channelToggle != null) {
            val (channel, enable) = channelToggle
            if (lower.contains("all") || lower.contains("everything")) {
                ChannelManager.Channel.values().forEach { ch ->
                    if (enable) ChannelManager.enable(ch) else ChannelManager.disable(ch)
                }
                val verb = if (enable) "enabled" else "paused"
                notify("All channels $verb:\n${ChannelManager.statusSummary()}")
            } else {
                if (enable) ChannelManager.enable(channel) else ChannelManager.disable(channel)
                val verb = if (enable) "enabled" else "paused"
                notify("${channel.name} $verb.\n${ChannelManager.statusSummary()}")
            }
            return
        }

        if (lower == "channels" || lower.contains("channel status")) {
            notify("Channel Status:\n${ChannelManager.statusSummary()}")
            return
        }

        try {
            // Fast-path: if parseSimpleCommand has a definitive answer, use it (no LLM)
            val quickResult = AgentLLMFacade.parseSimpleCommandPublic(input)
            val result = if (quickResult.action == "unknown" && looksLikeCommand(lower)) {
                Log.i(TAG, "handleAdminCommand: calling interpretCommand for '$input'")
                AgentLLMFacade.interpretCommand(input)
            } else if (quickResult.action != "unknown") {
                Log.d(TAG, "handleAdminCommand: fast-path '${quickResult.action}' — LLM skipped")
                quickResult
            } else {
                // Genuine conversation — no command keywords, not a known command
                Log.d(TAG, "handleAdminCommand: fast-path — genuine conversation")
                if (AgentLLMFacade.isReady()) {
                    val reply = AgentLLMFacade.generateChatReply(input)
                    notify(reply)
                    // If admin is chatting via SMS (not chat screen), reply back
                    if (message.channel != Message.Channel.CHAT) {
                        replyToSender(message, reply)
                    }
                } else {
                    notify("Try: status, channels, find [name], or just ask me anything.")
                    if (message.channel != Message.Channel.CHAT) {
                        replyToSender(message, "Loading... try again in a moment.")
                    }
                }
                return
            }

            when (result.action) {

                "find_contact" -> {
                    val target = result.target ?: run { notify("Who are you looking for?"); return }
                    val matches = ContactEngine.findAllByName(target)
                    when {
                        matches.isEmpty() -> {
                            val exact = ContactEngine.findContact(target)
                                ?: ContactEngine.findByRelationship(target)
                            notify(
                                if (exact != null) "Found:\n${ContactEngine.formatContact(exact)}"
                                else "No contact found for \"$target\"."
                            )
                        }
                        matches.size == 1 ->
                            notify("Found:\n${ContactEngine.formatContact(matches[0])}")
                        else -> {
                            val names = matches.mapIndexed { i, c ->
                                "${i+1}. ${c.name ?: c.phones.firstOrNull() ?: c.id}"
                            }.joinToString("\n")
                            notify("Found ${matches.size} matching \"$target\":\n$names")
                        }
                    }
                }

                "get_contact_phone", "contact_phone", "phone_number" -> {
                    val target = result.target ?: run { notify("Who are you looking for?"); return }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                        ?: ContactEngine.findAllByName(target).firstOrNull()
                    if (contact != null) {
                        val phones = contact.phones.joinToString(", ").ifEmpty { "no phone on file" }
                        val emails = contact.emails.joinToString(", ").ifEmpty { "no email on file" }
                        notify("${contact.name ?: target}:\nPhone: $phones\nEmail: $emails")
                    } else {
                        notify("No contact found for \"$target\". Try 'find $target'.")
                    }
                }

                "never_reply_to" -> {
                    val target = result.target ?: return
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    if (contact != null) {
                        ContactEngine.setInstructions(target, "never reply",
                            ContactEngine.ReplyBehavior.NEVER)
                        notify("Will never reply to ${contact.name ?: target}.")
                    } else {
                        notify("Contact \"$target\" not found.")
                    }
                }

                "always_reply_to" -> {
                    val target = result.target ?: return
                    ContactEngine.setInstructions(target, null, ContactEngine.ReplyBehavior.ALWAYS)
                    notify("Will always auto-reply to $target.")
                }

                "set_contact_instructions" -> {
                    val target = result.target ?: run {
                        notify("Who should I set instructions for?")
                        return
                    }
                    val instructions = result.content ?: run {
                        notify("What instructions should I follow for $target?")
                        return
                    }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    if (contact != null) {
                        ContactEngine.setInstructions(target, instructions, null)
                        notify("Instructions set for ${contact.name ?: target}:\n\"$instructions\"")
                    } else {
                        notify("Contact \"$target\" not found. Try 'find $target' first.")
                    }
                }

                "status" -> {
                    val ai = AgentLLMFacade.getStateLabel()
                    val contacts = ContactEngine.getCount()
                    val channels = ChannelManager.statusSummary()
                    val emailStatus = if (appContext?.let { com.aigentik.app.auth.GoogleAuthManager.isSignedIn(it) } == true)
                        "signed in" else "not signed in"
                    notify("$agentName Status:\nAI: $ai\nContacts: $contacts\nEmail: $emailStatus\nChannels:\n$channels")
                }

                "send_sms" -> {
                    notify("Sending new messages is not in my capabilities — I can only reply to messages I receive.")
                }

                "send_email" -> {
                    val to = result.target ?: run { notify("Who should I email?"); return }
                    val body = result.content ?: run { notify("What should I say?"); return }
                    val sent = EmailRouter.sendEmailDirect(to, "$agentName message", body)
                    notify(if (sent) "Email sent to $to." else "Failed to send email to $to. Check Gmail sign-in.")
                }

                "check_email", "read_email", "list_email" -> {
                    val ctx = appContext
                    if (ctx != null && com.aigentik.app.auth.GoogleAuthManager.isSignedIn(ctx)) {
                        val summary = GmailApiClient.countUnreadBySender(ctx)
                        if (summary.isEmpty()) {
                            notify("No unread emails.")
                        } else {
                            val top = summary.entries.sortedByDescending { it.value }.take(8)
                            val lines = top.joinToString("\n") { "${it.key}: ${it.value}" }
                            val total = summary.values.sum()
                            notify("$total unread email(s) by sender:\n$lines")
                        }
                    } else {
                        notify("Gmail not signed in — sign in via Settings to use email features.")
                    }
                }

                "sync_contacts" -> {
                    val ctx = appContext
                    if (ctx != null) {
                        val added = ContactEngine.syncAndroidContacts(ctx)
                        notify("Contacts synced: ${ContactEngine.getCount()} total ($added new).")
                    } else {
                        notify("Contacts: ${ContactEngine.getCount()} loaded.")
                    }
                }

                else -> {
                    // Phone/contact keyword shortcuts
                    val lower2 = input.lowercase()
                    when {
                        lower2.startsWith("text ") || lower2.startsWith("send ") ->
                            notify("Sending new messages is not in my capabilities — I can only reply to messages I receive.")

                        lower2.contains("email") ->
                            notify("Email active. Say 'check email' to see unread, or 'send email to [address]'.")

                        else -> {
                            // Genuine conversation that slipped past fast-path
                            if (AgentLLMFacade.isReady()) {
                                Log.d(TAG, "handleAdminCommand: fallback → generateChatReply")
                                val reply = AgentLLMFacade.generateChatReply(input)
                                notify(reply)
                                if (message.channel != Message.Channel.CHAT) {
                                    replyToSender(message, reply)
                                }
                            } else {
                                notify("Try: status, channels, find [name], check email, or just ask me anything.")
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handleAdminCommand: ${e.javaClass.simpleName}: ${e.message}", e)
            notify("Error: ${e.message?.take(80) ?: e.javaClass.simpleName}")
        }
    }

    // ─── Public message handler ───────────────────────────────────────────────

    private suspend fun handlePublicMessage(message: Message) {
        Log.i(TAG, "Public message from ${message.sender} via ${message.channel}")
        try {
            val contact = ContactEngine.findOrCreateByPhone(message.sender)

            if (contact.replyBehavior == ContactEngine.ReplyBehavior.NEVER) {
                Log.i(TAG, "Never-reply contact — skipping")
                return
            }

            val shouldAutoReply = contact.replyBehavior == ContactEngine.ReplyBehavior.ALWAYS ||
                contact.replyBehavior == ContactEngine.ReplyBehavior.AUTO

            // Notify owner if their name appears in the message
            if (message.body.lowercase().contains(ownerName.lowercase())) {
                val sender = contact.name ?: message.sender
                notify("URGENT: $sender mentioned your name!\n\"${message.body.take(100)}\"")
            }

            if (shouldAutoReply) {
                val reply = AgentLLMFacade.generateSmsReply(
                    senderName           = message.senderName ?: contact.name,
                    senderPhone          = message.sender,
                    message              = message.body,
                    relationship         = contact.relationship,
                    instructions         = contact.instructions,
                )

                when (message.channel) {
                    Message.Channel.NOTIFICATION -> {
                        val sent = NotificationReplyRouter.sendReply(message.id, reply)
                        if (!sent) {
                            Log.w(TAG, "Inline reply failed for ${message.sender}")
                            notify("Could not send reply to ${contact.name ?: message.sender} — notification was dismissed")
                        }
                    }
                    Message.Channel.EMAIL, Message.Channel.GVOICE -> {
                        EmailRouter.routeReply(message.sender, reply)
                    }
                    else -> Log.w(TAG, "Unknown channel ${message.channel} — cannot reply")
                }

                val sender = contact.name ?: message.sender
                notify("Auto-replied to $sender:\nThey: \"${message.body.take(60)}\"\nSent: \"${reply.take(80)}\"")
            } else {
                val sender = contact.name ?: message.sender
                notify("New message from $sender:\n\"${message.body.take(100)}\"\n\nSay \"always reply to $sender\" to auto-reply")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handlePublicMessage: ${e.javaClass.simpleName}: ${e.message}", e)
            notify("Error processing message from ${message.sender}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun notify(message: String) {
        ownerNotifier?.invoke(message)
        chatNotifier?.invoke(message)
    }

    private fun replyToSender(message: Message, reply: String) {
        when (message.channel) {
            Message.Channel.NOTIFICATION -> {
                val sent = NotificationReplyRouter.sendReply(message.id, reply)
                if (!sent) Log.w(TAG, "Inline reply failed for ${message.sender}")
            }
            Message.Channel.SMS    -> Log.w(TAG, "SMS channel reply skipped — SEND_SMS removed")
            Message.Channel.EMAIL,
            Message.Channel.GVOICE -> EmailRouter.routeReply(message.sender, reply)
            Message.Channel.CHAT   -> notify(reply)
        }
    }

    // Returns true if the lowercased input contains command-like keywords.
    // Used to decide: skip LLM (genuine conversation) vs call interpretCommand().
    private fun looksLikeCommand(lower: String): Boolean =
        lower.contains("email") || lower.contains("inbox") ||
        lower.startsWith("text ") || lower.contains("send ") || lower.contains("sms") ||
        lower.contains("contact") || lower.contains("number") || lower.contains("phone") ||
        lower.startsWith("find ") || lower.contains("look up") || lower.contains("sync")
}
