# Aigentik Fusion Report
**Date:** 2026-03-12
**Scope:** Full comparison of `aigentik-app` and `aigentik-android` with a definitive fusion strategy focused on SMS/RCS, Gmail, and tools integration.

---

## Executive Summary

`aigentik-app` has a superior technical foundation: modern Jetpack Compose UI, a robust multi-variant JNI inference module (`smollm`), a well-structured Room database, Koin DI with KSP, streaming LLM responses via Kotlin Flow, on-device vector search (`smolvectordb`), and Hugging Face model download. Its weakness is that every agent capability — SMS reply, Gmail operations, contact intelligence, channel management — is either a TODO stub or completely absent.

`aigentik-android` has a superior agent implementation: production-ready Gmail REST/OAuth2, notification-based SMS/RCS inline reply (no SMS permissions required), contact intelligence, rule engine, admin authentication, destructive action guards, message deduplication, conversation history, and a channel management system. Its weakness is a simpler JNI layer with no streaming, no multi-variant lib loading, no vector database, no HuggingFace integration, and a Views-based UI that predates Compose.

**The right path is clear: keep the aigentik-app infrastructure wholesale, and port the aigentik-android agent layer on top of it.** This is not a merge — it is a targeted transplant of specific production-hardened components into the correct slots of the aigentik-app architecture.

---

## Component-by-Component Analysis

---

### 1. LLM Inference Engine

#### aigentik-app: `smollm` module + `SmolLMManager`

**Keep. It is significantly better.**

`SmolLMManager` provides:
- **Streaming inference** via `Flow<String>` — each token is emitted as generated. aigentik-android blocks for the full response.
- **Multi-variant native library selection** — detects CPU features (SVE, i8mm, FP16, dotprod, ARMv8.2/v8.4) and loads the fastest available `.so`. aigentik-android loads a single `aigentik_llama.so`.
- **GGUF metadata reading** — reads context size and chat template directly from the model file. aigentik-android hardcodes these.
- **`limitedParallelism(1)` dispatcher** — serializes all JNI calls on a single coroutine thread, preventing concurrent context access without a ReentrantLock.
- **`GGUFReader` with `AutoCloseable`** — native handle is properly freed after metadata read.
- **Koin `@Single` injection** — aigentik-android's `LlamaJNI` is a raw singleton, not injectable.

**What aigentik-android adds that should be incorporated:**

`AiEngine` provides high-level generation methods with tailored system prompts:
- `generateSmsReply()` — concise, conversational, 256 tokens
- `generateEmailReply()` — professional, 512 tokens, 2000-char body intake
- `generateChatReply()` — no SMS framing, no signature, 512 tokens
- `interpretCommand()` — temperature=0.0 greedy JSON output, Qwen3 `<think>` block prefill to suppress chain-of-thought during command parsing
- `warmUp()` — fires a 4-token prompt after model load to prime JIT and KV cache

**How to incorporate:** Create `AgentLLMFacade.kt` in `com.aigentik.app.agent/` that wraps `SmolLMManager` and exposes these named generation methods. The key difference is that `SmolLMManager.getResponse()` is streaming-only, so `AgentLLMFacade` needs a non-streaming call path for background service use. This can be done by collecting the Flow to completion:

```kotlin
// AgentLLMFacade — wraps SmolLMManager for agent (non-streaming) use
@Single
class AgentLLMFacade(private val manager: SmolLMManager) {

    suspend fun generateSmsReply(
        senderName: String?, senderPhone: String, message: String,
        relationship: String? = null, instructions: String? = null
    ): String {
        if (!manager.isInstanceLoaded.get()) return fallbackSmsReply(senderName, senderPhone)
        val systemMsg = buildSmsSystemPrompt(senderName, senderPhone, relationship, instructions)
        val userTurn  = "Reply to: \"$message\" from ${senderName ?: senderPhone}"
        return runBlocking(jniDispatcher) {
            // collect full response — non-streaming for service-layer use
            var result = ""
            manager.instance.getResponseAsFlow(userTurn).collect { result = it }
            result.trim().ifEmpty { fallbackSmsReply(senderName, senderPhone) } + smsSignature()
        }
    }

    suspend fun interpretCommand(commandText: String): CommandResult { ... }
    // etc.
}
```

The Qwen3 `<think>` prefill trick from `AiEngine.interpretCommand()` should be ported verbatim — it saves 3-7 seconds per command call by suppressing chain-of-thought on models that support it.

---

### 2. SMS / RCS Handling

#### aigentik-app current state:

**Three disconnected pieces, none of which actually sends AI replies:**
- `SMSReceiver` (BroadcastReceiver) — receives `SMS_RECEIVED` intent, stores to `SMSRepository`, calls `triggerAIReplySuggestion()` which is a `Log.d` stub.
- `SMSService` — reads conversations/messages via `Telephony` content URIs, sends SMS via `SmsManager.sendMultipartTextMessage()`, `generateAIReply()` returns a hardcoded placeholder string.
- `AigentikNotificationListener` (NotificationListenerService) — intercepts notifications from many apps, extracts text, stores to `_incomingMessages` StateFlow, calls `triggerAIReplySuggestion()` which is again a `Log.d` stub. Has `sendQuickReply()` that can send an inline reply but is never called from the AI path.

**Critical problem with the current approach:** `SMSService.sendSMS()` and `SMSReceiver` both require `SEND_SMS` and `RECEIVE_SMS` permissions. These are dangerous permissions that trigger user-visible permission dialogs, require the app to be the default messaging app for full functionality, and are more likely to trigger Play Store scrutiny. More importantly, **they do not work for RCS** — RCS messages are not accessible via `SmsManager` or `SMS_RECEIVED` broadcasts.

#### aigentik-android approach:

**No SMS permissions. No `SmsManager`. No `SMSReceiver`. No `RECEIVE_SMS`.**

Instead, it uses Samsung/Google Messages' own notification system:
- `NotificationAdapter` (NotificationListenerService) intercepts notifications from `com.samsung.android.messaging` and `com.google.android.apps.messaging`.
- `NotificationReplyRouter` sends replies by invoking the messaging app's own `RemoteInput PendingIntent` — the same mechanism used by Android's smart reply feature.
- `MessageDeduplicator` prevents self-reply loops (Samsung updates the conversation notification after an inline reply is sent, which would be re-processed as a new incoming message without deduplication).

**This approach covers both SMS and RCS** with a single code path because Samsung Messages and Google Messages handle both transports and expose them through the same notification API.

**Decision: Abandon `SMSReceiver` and `SMSService.sendSMS()`. Port `NotificationAdapter`, `NotificationReplyRouter`, and `MessageDeduplicator` from aigentik-android.**

Specific changes required:

1. **Remove from `AndroidManifest.xml`:** `SEND_SMS`, `RECEIVE_SMS` permissions and `SMSReceiver` receiver declaration.

2. **Add to `AndroidManifest.xml`:** `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permissions. The `AigentikNotificationListener` service entry needs `android:exported="true"` to accept system binding (currently `false` — this prevents the OS from binding to it).

3. **Replace `AigentikNotificationListener`** with aigentik-android's `NotificationAdapter`. The aigentik-app version stores messages to a StateFlow; the android version routes them to `MessageEngine`. In the fused version, `NotificationAdapter` routes to the new `AgentMessageEngine` (see section 6). Gmail notification routing (detecting `com.google.android.gm` package) should also be preserved.

4. **Port `NotificationReplyRouter` verbatim.** The `ConcurrentHashMap` dual-key design (messageId → sbnKey, sbnKey → ReplyEntry), the package-specific `REPLY_KEYS` lookup table, and the `markSent()`/`wasSentRecently()` self-reply loop prevention are all production-hardened and correct.

5. **Port `MessageDeduplicator` verbatim.** The body-only fingerprint (no timestamp — prevents false misses when carrier timestamp and notification timestamp span a minute boundary) is a subtle correctness fix that took iterations to get right.

6. **`SMSService` should be kept but narrowed.** Its `getConversations()` and `getThreadMessages()` methods (read-only Telephony queries) are useful for a potential future SMS inbox UI feature. Its `sendSMS()`, `generateAIReply()`, and `generateAIReplySuggestions()` methods should be removed — sending is done via `NotificationReplyRouter` and AI replies are generated by `AgentLLMFacade`.

---

### 3. Gmail Integration

#### aigentik-app current state:

`EmailService.kt` is a full scaffold with well-designed method signatures for every Gmail operation (read inbox, send, GVoice detection, label, trash, search, mark spam, unsubscribe). **Every method body is a TODO stub returning a hardcoded placeholder.** There is no OAuth2 implementation. There are no actual HTTP calls.

`EmailModels.kt` defines `EmailMessage`, `EmailAccount`, `EmailAttachment`, `EmailLabel`, `EmailFolder`, and `EmailProvider` — these are reasonable model classes.

#### aigentik-android approach:

**Production-ready, multi-version hardened Gmail implementation across four files:**

**`GmailApiClient`** (641 lines):
- Direct Gmail REST API via OkHttp (no `google-api-services-gmail` library needed)
- All HTTP responses wrapped in `.use {}` to release socket connections
- `listUnread()`, `getFullEmail()`, `sendEmail()`, `replyToEmail()`, `deleteEmail()`, `markAsRead()`, `markAsSpam()`, `searchEmails()`, `deleteAllMatching()` (batchModify, O(ceil(N/1000)) calls instead of N), `emptyTrash()`, `getOrCreateLabel()`, `addLabel()`, `getUnsubscribeLink()`
- `listUnreadSummary()` — metadata-only fast path for counting, avoids fetching full bodies
- `countUnreadBySender()` — groups unread by sender display name
- Recursive MIME body extraction (`extractBodyRecursive()`) up to depth 10, caps HTML at 16KB before `Html.fromHtml()`, wraps `Html.fromHtml()` in `catch(Throwable)` to handle OOM/StackOverflowError, caps final body at 4000 chars
- GVoice subject-line detection and parsing (`isGoogleVoiceText()`, `parseGoogleVoiceEmail()`) with regex for individual and group texts, GVoice footer stripping
- Named error states in `lastError` for diagnostics

**`GmailHistoryClient`** (214 lines):
- History API delta-based fetch — processes only messages added since a known historyId
- SharedPreferences persistence for historyId across service restarts
- `PrimeResult` enum for specific error reporting (NO_TOKEN, API_ERROR, NETWORK_ERROR vs ALREADY_STORED/PRIMED)
- 404 handling: historyId purge detection → re-prime from Gmail profile

**`EmailMonitor`** (260 lines):
- Notification-triggered (no polling, no cloud relay)
- `AtomicBoolean.compareAndSet(false, true)` — atomically prevents duplicate fetch coroutines from rapid notification bursts (vs `@Volatile Boolean` which has a check-then-set race)
- `CoroutineExceptionHandler` added to scope — prevents OOM/StackOverflowError in launched coroutines from reaching `UncaughtExceptionHandler` and killing the process
- History API primary path → listUnread fallback (capped at 3 on fallback to prevent email flood crash)
- Own-email filtering prevents auto-reply loops
- GVoice routing through `ChannelManager.GVOICE`

**`EmailRouter`** (147 lines):
- Routes replies: GVoice → `GmailApiClient.replyToGoogleVoiceText()`, regular email → `GmailApiClient.replyToEmail()`
- Context maps keyed by sender (not messageId) — ensures replies always use the most recent email in thread
- `ConcurrentHashMap` for thread-safe concurrent access
- Cap at 200 entries per map to prevent unbounded memory growth
- `notifyOwner()` — sends status email to the signed-in account

**Decision: Replace `EmailService` with `GmailApiClient` + `GmailHistoryClient` + `EmailMonitor` + `EmailRouter` from aigentik-android verbatim.**

`EmailModels.kt` from aigentik-app can be kept for the UI model layer (EmailMessage, EmailLabel, etc.) but these should be kept separate from the transport-layer `ParsedEmail` / `GoogleVoiceMessage` used by `GmailApiClient`. The data flows are: `GmailApiClient` produces `ParsedEmail` → `EmailMonitor` maps to `Message` → `AgentMessageEngine` processes → `EmailRouter` sends via `GmailApiClient`.

---

### 4. OAuth2 / Google Authentication

#### aigentik-app current state:

The app has `google-services.json`, Firebase integration, and a Credential Manager-based sign-in (`com.google.android.libraries.identity.googleid`) declared as a dependency in `app/build.gradle.kts`. However, there is no `GoogleAuthManager` equivalent — no token refresh logic, no scope management, no stored account persistence.

#### aigentik-android approach:

`GoogleAuthManager` (185 lines):
- `GoogleSignIn.getLastSignedInAccount()` for session restore on startup
- Incremental scope granting: basic `contacts.readonly` at sign-in, Gmail scopes (`gmail.modify`, `gmail.send`) via `GoogleAuthUtil.getToken()` incrementally
- **Critical fix:** `UserRecoverableAuthException` is caught specifically. Gmail scopes are restricted — they require explicit user consent via a system-provided dialog. Without catching this exception and storing its resolution `Intent`, the consent dialog never appears and Gmail silently fails.
- `pendingScopeIntent` stored for any Activity to launch
- `scopeResolutionListener` callback for notifying the UI (e.g. SettingsActivity)
- `gmailScopesGranted` flag for dashboard display
- `lastTokenError` for specific diagnostics

**Decision: Port `GoogleAuthManager` from aigentik-android.** The aigentik-app's Credential Manager-based approach can coexist for sign-in UI, but `getFreshToken()` must use `GoogleAuthUtil.getToken()` with the incremental scope approach or Gmail calls will silently fail with `UserRecoverableAuthException` unhandled.

**Important:** aigentik-android uses `com.google.android.gms:play-services-auth` (GoogleSignIn) and `GoogleAuthUtil`. aigentik-app uses the newer `androidx.credentials` / `com.google.android.libraries.identity.googleid` stack. Both are valid, but the token-fetching logic must use `GoogleAuthUtil.getToken()` for service-layer (background) token refresh — the Credentials API is UI-only and cannot run in a background service.

---

### 5. Foreground Service + Boot Receiver

#### aigentik-app current state:

**No foreground service. No boot receiver.**

The app has no `AigentikService` equivalent. SMS/email processing requires the app to be in the foreground or for Android to have kept it alive. On Samsung devices, background processes are aggressively killed. There is no mechanism to restart the agent after reboot.

#### aigentik-android approach:

`AigentikService` (203 lines):
- `Service` subclass with `foregroundServiceType="dataSync"` (appropriate for background network + inference)
- `PARTIAL_WAKE_LOCK` acquired on startup and passed to `MessageEngine` for per-message acquisition during llama.cpp inference. Without this, Samsung throttles background CPU, increasing inference time from ~30s to 5+ minutes.
- `START_STICKY` — Android restarts the service if killed
- `SupervisorJob` scope — individual init failures don't cancel the entire startup
- Correct init ordering: `MessageEngine.configure()` (sets wake lock) is called **before** `EmailMonitor.init()` — a subtle ordering dependency (if reversed, a Gmail notification arriving between the two calls processes with `wakeLock=null`)
- Notification channel + persistent foreground notification with status text
- `GmailHistoryClient.primeHistoryId()` runs in a separate coroutine (non-blocking)
- `ConnectionWatchdog` for network resilience

`BootReceiver` (auto-restart after `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`):
```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, AigentikService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
```

**Decision: Port `AigentikService` and `BootReceiver` from aigentik-android.** The fused version should use Koin to inject dependencies where possible (e.g. inject `SmolLMManager` rather than using `AiEngine.loadModel(path)` directly). The wake lock pattern must be preserved exactly — Samsung CPU throttling during background inference is real and severe.

---

### 6. MessageEngine and Command Routing

#### aigentik-app current state:

**No equivalent.** There is no component that receives a message and decides whether to treat it as an admin command, a public auto-reply trigger, or a channel toggle. The `ChatScreenViewModel` handles UI-layer chat but has no connection to SMS/email events.

#### aigentik-android approach:

`MessageEngine` (400+ lines, v2.2) is the central routing hub:

- Receives `Message` objects from `NotificationAdapter` (SMS/RCS) and `EmailMonitor` (Gmail/GVoice)
- `Mutex` serialization — **only one message handler runs at a time.** This is critical. Without it, a burst of 10 emails on first install triggers 10 concurrent `interpretCommand()` calls, each resetting the 128MB native KV context, causing memory fragmentation and native crashes.
- Admin path (`handleAdminCommand`): checks `AdminAuthManager`, `DestructiveActionGuard`, `ChannelManager` toggle parsing, then calls `AiEngine.interpretCommand()` → dispatches to Gmail/contact/channel actions
- Fast-path optimization: `parseSimpleCommandPublic()` (pure regex, no LLM) runs first; if it returns a known action, `interpretCommand()` (LLM) is skipped entirely. Only truly ambiguous phrasing reaches the LLM.
- Public path (`handlePublicMessage`): loads per-contact conversation history, applies `RuleEngine` filtering, generates AI reply, routes via `NotificationReplyRouter` (SMS/RCS) or `EmailRouter` (email)
- Per-contact conversation history: last N turns stored in Room, topic-drift gap detection
- `generateChatReply()` vs `generateSmsReply()` routing: chat messages get a conversational prompt, public SMS/email get a formal agent prompt with signature

**Decision: Port `MessageEngine` from aigentik-android, adapted to use `AgentLLMFacade` (which wraps `SmolLMManager`) instead of `AiEngine` (which wraps `LlamaJNI`).**

The `chatNotifier` lambda should post to the existing `AppDB.addAssistantMessage()` for the active chat, rather than to a separate `ChatDatabase`. This makes service-layer events visible in the existing Compose chat UI.

---

### 7. Contact Intelligence

#### aigentik-app current state:

`AigentikNotificationListener` calls `getContactNameByAddress()` from `SMSService`, which does a `ContactsContract.PhoneLookup` query for display name lookup. That's the extent of contact intelligence — display name resolution only.

#### aigentik-android approach:

`ContactEngine` (305 lines, v0.6):
- Syncs all Android contacts into a Room-backed store with Aigentik-specific metadata: `instructions` (per-contact AI behavior), `replyBehavior` (AUTO/ALWAYS/NEVER/REVIEW), `relationship` (e.g. "mom", "boss"), `aliases`, `notes`
- **`CopyOnWriteArrayList` for the in-memory cache** — lock-free reads from any thread, correct on concurrent access from Main + IO. `atomicReference` replacement in `loadFromRoom()` — readers never see an empty intermediate state during initialization.
- `findContact()` matches by phone (last 10 digits), email (case-insensitive), full name, partial name, and aliases
- `findByRelationship()` — "text mom" resolves to the contact with `relationship = "mom"`
- `findAllByName()` — returns all matches for disambiguation
- One-time migration from legacy `contacts.json` to Room

**Decision: Port `ContactEngine` from aigentik-android.** The `Contact` entity should be added to the existing `AppDB` / `AppRoomDatabase` rather than creating a separate `ContactDatabase`, keeping the database count at one. The `ContactDao` goes into `AppDB` alongside `ChatsDao`, `ChatMessageDao`, etc.

---

### 8. Rule Engine

#### aigentik-app current state:

**No equivalent.**

#### aigentik-android approach:

`RuleEngine` (261 lines, v0.5):
- In-memory rule lists (fast evaluation) backed by Room for persistence
- SMS rules: `from_number`, `message_contains`, `any` condition types
- Email rules: `from`, `domain`, `subject_contains`, `body_contains`, `promotional` (auto-detected: unsubscribe link, no-reply, newsletter), `any`
- Actions: `AUTO_REPLY`, `SPAM`, `REVIEW`, `DEFAULT`
- Newest-first evaluation (newer rules take priority)
- One-time migration from legacy JSON files
- Async Room writes via `ioScope` to prevent main-thread database access

**Decision: Port `RuleEngine` from aigentik-android.** Add `RuleEntity` to the existing `AppRoomDatabase`. The promotional email auto-detection is particularly valuable for filtering newsletters and automated messages before they reach the LLM.

---

### 9. Admin Authentication + Destructive Action Guard

#### aigentik-app current state:

**No equivalent.**

#### aigentik-android approach:

`AdminAuthManager`:
- Parses `Admin: [name]\nPassword: [password]\n[command]` format from any channel
- SHA-256 password hashing stored in SharedPreferences
- 30-minute session timeout per channel
- `ConcurrentHashMap` for concurrent session access

`DestructiveActionGuard`:
- Two-step confirmation for irreversible Gmail operations (trash, empty trash, spam, unsubscribe)
- 5-minute expiry for pending confirmations
- Common-word exclusion list prevents accidental confirmation via words like "yes", "delete", "ok"
- `ConcurrentHashMap` for thread-safe pending action storage

**Decision: Port both verbatim.** These are critical safety components for the grant's research claims about policy enforcement. `DestructiveActionGuard` is the precursor to the full `ActionPolicyEngine` described in `CLAUDE.md` — port it as-is and extend it into the full policy engine in Phase I.

---

### 10. Channel Management

#### aigentik-app current state:

**No equivalent.**

#### aigentik-android approach:

`ChannelManager` (81 lines):
- `Channel` enum: SMS, GVOICE, EMAIL
- Per-channel enable/disable with `AigentikSettings.setChannelEnabled()` persistence
- `parseToggleCommand()` — natural language toggle parsing ("stop email", "enable sms", "pause everything")
- `loadFromSettings()` — restores state on service start
- `statusSummary()` — human-readable channel status for the status command

**Decision: Port verbatim.** The persistence can use Koin-injected `SharedPreferences` or a Room entity — either works.

---

### 11. UI Architecture

#### aigentik-app: Jetpack Compose

**Keep. It is significantly better.**

Jetpack Compose is the current Android UI standard. It provides:
- Declarative state-driven UI — no `notifyDataSetChanged()`, no `ViewHolder` boilerplate
- `StateFlow` collection via `collectAsState()` — direct connection between ViewModel state and UI
- Navigation with typed routes
- Better testability

`ChatScreenViewModel` is well-structured with `viewModelScope`, `withContext(Dispatchers.IO)` for DB operations, and a `ChatScreenUIState` data class.

#### aigentik-android: Views-based Activities

`ChatActivity`, `SettingsActivity`, `ModelManagerActivity` etc. are traditional Views-based Activities. They work, but they require significantly more boilerplate to maintain.

**Decision: Keep the Compose UI from aigentik-app for all existing screens.** New screens needed for the agent features (settings, rule manager, contact detail) should be built in Compose. The aigentik-android Activities can serve as reference implementations for what data to show and what actions to expose, but should be re-implemented in Compose rather than ported as-is.

**Exception:** The `ChatBridge` pattern from aigentik-android should be adapted for the Compose architecture. Instead of writing to a separate `ChatDatabase`, service-layer events (incoming SMS auto-replies, email responses) should write to the existing `AppDB.addAssistantMessage()` for the active chat, making them appear in `ChatScreenViewModel`'s observed state.

---

### 12. Dependency Injection

#### aigentik-app: Koin with KSP (`@Single`, `@Factory`, `@Module`, `@ComponentScan`)

**Keep. It is more modern and faster to compile.**

KAPT (used in aigentik-android) is deprecated. KSP is the current annotation processor and compiles significantly faster.

#### aigentik-android: Manual singletons (`object`) with no DI

`AiEngine`, `MessageEngine`, `ContactEngine`, `RuleEngine`, `ChannelManager`, `EmailMonitor`, `EmailRouter`, `NotificationReplyRouter`, `MessageDeduplicator` are all Kotlin `object` singletons — process-global, no lifecycle management, no injection.

**Decision:** When porting aigentik-android components, convert from `object` to `class` annotated with `@Single` where appropriate, allowing Koin to manage lifecycle and inject them. Components that genuinely need to be accessible from `BroadcastReceiver` or `NotificationListenerService` (which are Android-instantiated, not Koin-managed) should remain accessible via `KoinComponent.by inject()` as aigentik-app's `SMSReceiver` already demonstrates.

Example conversion:
```kotlin
// aigentik-android:
object ContactEngine { ... }
ContactEngine.init(context)

// fused version:
@Single
class ContactEngine(context: Context) {
    init { syncAndroidContacts(context) }
    ...
}
// Koin injects it where needed
```

---

### 13. Database Architecture

#### aigentik-app: Single `AppRoomDatabase` with `AppDB` wrapper

**Keep this pattern.** The existing entities are: `Chat`, `ChatMessage`, `LLMModel`, `Task`, `Folder`.

**What to add** (all as new entities in the same `AppRoomDatabase`):

| Entity | Source | Purpose |
|--------|--------|---------|
| `Contact` | aigentik-android `ContactEntity` | Contact intelligence, per-contact instructions |
| `SmsRule` / `EmailRule` | aigentik-android `RuleEntity` | Message filtering rules |
| `ConversationHistory` | aigentik-android | Per-contact conversation turns for AI context |
| `BenchmarkResult` | CLAUDE.md roadmap | Phase I benchmark metrics |

Do not create separate databases for contacts, rules, or conversation history — one database is simpler, and Room handles multiple DAOs in a single database cleanly.

**Database version must be incremented** when new entities are added. Add a migration strategy (either `addMigrations()` or `fallbackToDestructiveMigration()` for dev builds).

---

### 14. smolvectordb + hf-model-hub-api

**Keep both.** These are unique to aigentik-app and absent from aigentik-android.

`smolvectordb` is the on-device vector database needed for the Phase I memory system (`MemoryRetriever` in CLAUDE.md). No equivalent exists in aigentik-android.

`hf-model-hub-api` provides Hugging Face model search and download, enabling the model download UI. aigentik-android requires manual model placement.

---

## Summary Decision Table

| Component | Decision | Rationale |
|-----------|----------|-----------|
| Jetpack Compose UI | **Keep aigentik-app** | Modern, declarative, no Views boilerplate |
| `SmolLMManager` + `smollm` module | **Keep aigentik-app** | Streaming Flow, multi-variant libs, GGUF metadata, Koin-injected |
| `GGUFReader` + `AutoCloseable` | **Keep aigentik-app** | Correct native handle lifecycle |
| Room `AppDB` + `AppRoomDatabase` | **Keep aigentik-app** | Extend with new entities, don't split |
| Koin DI with KSP | **Keep aigentik-app** | KAPT deprecated, KSP faster |
| `smolvectordb` | **Keep aigentik-app** | No equivalent in android, needed for memory system |
| `hf-model-hub-api` | **Keep aigentik-app** | No equivalent in android, needed for model discovery |
| `SMSReceiver` | **Remove** | Replaced by `NotificationReplyRouter` (no SMS perms needed) |
| `SMSService.sendSMS()` | **Remove** | Replaced by inline reply (works for RCS too) |
| `SMSService` read methods | **Keep** | Useful for future SMS inbox UI |
| `AigentikNotificationListener` | **Replace** | Port `NotificationAdapter` from android |
| `NotificationReplyRouter` | **Port from android** | Production-hardened inline reply, no SMS permissions |
| `MessageDeduplicator` | **Port from android** | Self-reply prevention, TTL-based dedup |
| `EmailService` (stub) | **Replace** | All TODO stubs — port `GmailApiClient` instead |
| `EmailModels` | **Keep for UI layer** | Keep `EmailMessage` etc. for any UI screens |
| `GmailApiClient` | **Port from android** | Production-ready, OkHttp, recursive MIME, batchModify |
| `GmailHistoryClient` | **Port from android** | Delta-based fetch, PrimeResult enum |
| `EmailMonitor` | **Port from android** | AtomicBoolean, CoroutineExceptionHandler, notification-triggered |
| `EmailRouter` | **Port from android** | ConcurrentHashMap, GVoice routing, correct context keying |
| `GoogleAuthManager` | **Port from android** | UserRecoverableAuthException handling is critical |
| `AigentikService` | **Port from android, adapt to Koin** | Wake lock, START_STICKY, correct init order |
| `BootReceiver` | **Port from android** | Auto-restart after reboot |
| `MessageEngine` | **Port from android, adapt to Koin + SmolLMManager** | Mutex, fast-path, admin routing, conversation history |
| `AiEngine` | **Adapt as `AgentLLMFacade`** | Port prompt design + interpretCommand; replace LlamaJNI with SmolLMManager |
| `ContactEngine` | **Port from android, add entity to AppDB** | CopyOnWriteArrayList, per-contact instructions |
| `RuleEngine` | **Port from android, add entity to AppDB** | Room-backed, promotional auto-detection |
| `ChannelManager` | **Port from android** | Channel toggle with persistence |
| `ChatBridge` | **Port pattern, write to AppDB** | Service events appear in existing Compose chat UI |
| `AdminAuthManager` | **Port from android** | SHA-256, session management |
| `DestructiveActionGuard` | **Port from android** | Two-step confirmation, precursor to PolicyEngine |
| `LlamaJNI` | **Do not port** | Replaced by `SmolLMManager` which is superior |

---

## Recommended Implementation Order

Following the priority order in `CLAUDE.md`, which is grant-driven:

### Step 1: SMS/RCS inline reply (unblocks all agent action)

1. Remove `SMSReceiver` from `AndroidManifest.xml` and remove `SEND_SMS`, `RECEIVE_SMS`
2. Add `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
3. Replace `AigentikNotificationListener` with `NotificationAdapter`
4. Port `NotificationReplyRouter`, `MessageDeduplicator`
5. Wire `NotificationAdapter` → new stub `AgentMessageEngine.onMessageReceived()` that logs receipt (no LLM yet)
6. Test: send a test SMS to the device → confirm `AgentMessageEngine.onMessageReceived()` is called → confirm inline reply sends

### Step 2: Gmail OAuth + GmailApiClient

1. Port `GoogleAuthManager` (adapt Credential Manager sign-in UI to store account for `GoogleAuthUtil.getToken()` use)
2. Port `GmailApiClient`, `GmailHistoryClient`
3. Port `EmailMonitor`, `EmailRouter`
4. Add Gmail notification detection to `NotificationAdapter`
5. Wire `EmailMonitor` → `AgentMessageEngine.onMessageReceived()`
6. Test: receive a Gmail notification → confirm email is fetched, parsed, routed

### Step 3: AgentLLMFacade + AiEngine port

1. Create `AgentLLMFacade` wrapping `SmolLMManager` with named generation methods
2. Port `AiEngine`'s prompt templates and `interpretCommand()` logic into `AgentLLMFacade`
3. Port Qwen3 `<think>` prefill trick for command parsing
4. Implement `parseSimpleCommand()` for fast-path command recognition (no LLM)
5. Test: load a model, send "check my emails" → confirm correct action is dispatched

### Step 4: MessageEngine + full routing

1. Port `MessageEngine` adapted to use `AgentLLMFacade` and Koin
2. Port `ContactEngine` with `Contact` entity added to `AppRoomDatabase`
3. Port `RuleEngine` with `RuleEntity` added to `AppRoomDatabase`
4. Port `ChannelManager`
5. Port `ChatBridge` adapted to write to `AppDB`
6. Wire SMS inline reply path: `NotificationAdapter` → `MessageEngine` → `AgentLLMFacade.generateSmsReply()` → `NotificationReplyRouter.sendReply()`
7. Wire email reply path: `EmailMonitor` → `MessageEngine` → `AgentLLMFacade.generateEmailReply()` → `EmailRouter.routeReply()`

### Step 5: Service infrastructure + admin

1. Port `AigentikService` adapted to Koin (inject `SmolLMManager`, `MessageEngine`, etc.)
2. Port `BootReceiver`
3. Port `AdminAuthManager`, `DestructiveActionGuard`
4. Add Gmail NL command dispatch to `MessageEngine`: count unread, list unread, search, trash, mark read, etc.
5. Test full remote admin flow: send `Admin: [name]\nPassword: [pw]\ncheck my emails` via SMS → confirm Gmail response arrives back

### Step 6: Policy Engine (CLAUDE.md Phase I priority)

Extend `DestructiveActionGuard` into the full `ActionPolicyEngine`:
- `ActionSchema.kt` — sealed class hierarchy for all action types (already described in `CLAUDE.md`)
- `ActionSchemaValidator.kt` — validates `MessageEngine` output against expected action schema
- `RiskScorer.kt` — maps action type → risk tier
- `ActionPolicyEngine.kt` — combines schema + risk + confidence → allow/deny/defer decision
- `ApprovalWorkflow.kt` — suspends action, shows confirmation UI

The `DestructiveActionGuard` maps directly onto this: its pending action pattern becomes `ApprovalWorkflow`, its keyword list becomes `RiskScorer`, and its two-step password confirmation becomes one approval modality.

---

## A Better Way: Three Specific Improvements Beyond Either Codebase

### Improvement 1: Unified Agent Inference Interface

Both codebases mix inference configuration (temperature, tokens, system prompt) into the caller. aigentik-app's `SmolLMManager` is generic (any query, any params), and aigentik-android's `AiEngine` hardcodes everything inline. A better design:

```kotlin
// AgentTask.kt — typed inference request
sealed class AgentTask {
    data class SmsReply(val senderName: String?, val phone: String, val message: String,
                        val relationship: String?, val instructions: String?,
                        val history: List<String> = emptyList()) : AgentTask()
    data class EmailReply(val from: String, val subject: String, val body: String,
                          val relationship: String?, val instructions: String?,
                          val history: List<String> = emptyList()) : AgentTask()
    data class CommandParse(val commandText: String) : AgentTask()
    data class ChatReply(val message: String, val history: List<String> = emptyList()) : AgentTask()
}

// AgentLLMFacade.kt
@Single
class AgentLLMFacade(private val manager: SmolLMManager) {
    suspend fun execute(task: AgentTask): String = when (task) {
        is AgentTask.SmsReply    -> generateSmsReply(task)
        is AgentTask.EmailReply  -> generateEmailReply(task)
        is AgentTask.CommandParse -> interpretCommand(task.commandText)
        is AgentTask.ChatReply   -> generateChatReply(task)
    }
}
```

This makes `AgentTask` serializable to Room for the benchmark infrastructure (you can record exactly what task was dispatched, measure latency, and compare against a baseline). This directly serves the grant's benchmark requirement.

### Improvement 2: Single Notification Service with Role Separation

Both codebases put routing logic inside the `NotificationListenerService`. This is fragile because the service is instantiated by Android, not by Koin, and testing requires a real device.

Better: keep the `NotificationListenerService` as a thin adapter that only extracts raw data and forwards it, and put all routing logic in a Koin-injected `NotificationRouter` class that can be unit-tested:

```kotlin
class NotificationAdapter : NotificationListenerService(), KoinComponent {
    private val router: NotificationRouter by inject()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        router.route(sbn, applicationContext)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        router.onRemoved(sbn.key)
    }
}

@Single
class NotificationRouter(
    private val messageEngine: AgentMessageEngine,
    private val emailMonitor: EmailMonitor,
    private val replyRouter: NotificationReplyRouter,
    private val deduplicator: MessageDeduplicator
) {
    fun route(sbn: StatusBarNotification, context: Context) { ... }
    fun onRemoved(sbnKey: String) { ... }
}
```

This is the same pattern aigentik-app already uses for `SMSReceiver` + `SMSRepository`, but done correctly with full routing logic in the injectable class.

### Improvement 3: Conversation History in AppDB, Not a Separate Database

aigentik-android creates a separate `ConversationHistoryDatabase`. Combined with `ContactDatabase`, `RuleDatabase`, and `ChatDatabase`, that's four separate Room databases. This is unnecessary overhead (four SQLite connections, four `RoomDatabase` instances) and makes queries across entities require in-memory joining.

The better approach, consistent with aigentik-app's existing single-database architecture:

```kotlin
// Add to AppRoomDatabase:
@Database(
    entities = [Chat::class, ChatMessage::class, LLMModel::class, Task::class, Folder::class,
                Contact::class, Rule::class, ConversationTurn::class, BenchmarkResult::class],
    version = 2,
)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun ruleDao(): RuleDao
    abstract fun conversationHistoryDao(): ConversationHistoryDao
    abstract fun benchmarkDao(): BenchmarkDao
    // ... existing DAOs
}
```

One database, one connection, one migration path, full cross-entity querying if ever needed.

---

## Permissions Comparison

| Permission | aigentik-app | aigentik-android | Fused recommendation |
|------------|-------------|-----------------|----------------------|
| `INTERNET` | ✅ | ✅ | Keep |
| `READ_SMS` | ✅ | ❌ | **Remove** — not needed with notification approach |
| `SEND_SMS` | ✅ | ❌ | **Remove** — inline reply via RemoteInput |
| `RECEIVE_SMS` | ✅ | ❌ | **Remove** — notification-triggered |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | ✅ | ✅ | Keep |
| `WAKE_LOCK` | ❌ | ✅ | **Add** — critical for Samsung CPU throttle prevention |
| `FOREGROUND_SERVICE` | ✅ | ✅ | Keep |
| `FOREGROUND_SERVICE_DATA_SYNC` | ❌ | ✅ | **Add** — required for foreground service type |
| `POST_NOTIFICATIONS` | ✅ | ✅ | Keep |
| `RECEIVE_BOOT_COMPLETED` | ❌ | ✅ | **Add** — BootReceiver |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ❌ | ✅ | **Add** — stay alive in background |
| `READ_CONTACTS` | ❌ | ✅ | **Add** — ContactEngine.syncAndroidContacts() |
| `RECORD_AUDIO` | ✅ | ❌ | Keep for ASR |
| `READ_CALENDAR` / `WRITE_CALENDAR` | ✅ | ❌ | Keep for calendar integration |
| `GET_ACCOUNTS` / `USE_CREDENTIALS` | ✅ | ❌ | Keep for OAuth2 sign-in |

---

## Build System Notes

aigentik-android uses KAPT (`alias(libs.plugins.kotlin.kapt)`) for Room annotation processing. aigentik-app uses KSP. When porting Room entities and DAOs from aigentik-android, use KSP processor syntax (already in aigentik-app's build setup). No changes to the build system are required.

aigentik-android uses Gson for JSON parsing in `GmailApiClient`. aigentik-app does not have Gson. When porting, either add Gson (`com.google.code.gson:gson:2.10.1`) to `app/build.gradle.kts` dependencies, or replace Gson usages with `org.json.JSONObject` (which is already used in `AiEngine.parseCommandJson()`) or `kotlinx.serialization`.

aigentik-android adds `javamail.android` and `javamail.activation` for MIME message building in `GmailApiClient.sendEmail()`. These must be added to aigentik-app's dependencies and the `META-INF/DEPENDENCIES` packaging exclusion (already present from the earlier build fix) will handle the duplicate file conflict.

---

*End of report.*
