# Aigentik Development Log

Running log of all implementation sessions. Update this at the end of every session.
Format: `## YYYY-MM-DD — Summary` then bullet points of what was done, tested, and what's next.

---

## 2026-03-12 — Session 1: Build Fix + SMS/RCS Foundation + Foreground Service

### Fixed
- `ManageTasksActivity.kt` line 181: missing `}` closing `if (showCreateTaskDialog)` block — was causing KSP compile failure in CI (`:app:kspDebugKotlin FAILED`)

### Implemented
- `app/src/main/res/xml/notification_listener.xml` — required metadata for OS to recognize NotificationListenerService on Samsung. Without this, app never appears in Settings → Notification Access
- `AndroidManifest.xml` — removed `READ_SMS`/`SEND_SMS`/`RECEIVE_SMS` permissions and `SMSReceiver` declaration; added `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `READ_CONTACTS`, `FOREGROUND_SERVICE_DATA_SYNC`; fixed `android:exported="true"` on notification service; added `<meta-data android:name="android.app.notification_listener_settings">` entry
- `AigentikNotificationListener.kt` — rewrote with `onListenerConnected()`/`onListenerDisconnected()`, `FLAG_GROUP_SUMMARY` check, KoinComponent injection of `AgentMessageEngine`, `NotificationReplyRouter`, `MessageDeduplicator`
- `NotificationReplyRouter.kt` (`sms/`) — ConcurrentHashMap inline reply via RemoteInput PendingIntent; self-reply loop guard
- `MessageDeduplicator.kt` (`sms/`) — body-only TTL fingerprint; prevents Samsung post-reply notification re-processing
- `AgentMessageEngine.kt` (`agent/`) — Step 1 stub; logs all inbound messages; ready for LLM wiring in Stage 1
- `AigentikService.kt` (`core/`) — foreground service with `PARTIAL_WAKE_LOCK` + `START_STICKY`; prevents Samsung killing process in background
- `BootReceiver.kt` (`system/`) — restarts service on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`
- `MainActivity.kt` — starts `AigentikService` on launch; requests battery optimization exemption
- `ic_notification.xml` drawable — simple status bar icon for foreground service notification

### Tested & Confirmed
- `adb shell settings get secure enabled_notification_listeners` — confirmed `com.aigentik.app/com.aigentik.app.sms.AigentikNotificationListener` is registered
- `adb logcat -s AigentikNotifListener` — confirmed `NotificationListenerService connected — ready to receive notifications` fires on launch

### Remaining Issue
- Notification listener connects but SMS messages not yet triggering `AgentMessageEngine.onMessageReceived()` — pre-work and Stage 1 (MessageEngine + AgentLLMFacade) needed to complete the pipeline

---

## 2026-03-12 — Session 2: Pre-Work Complete

### Implemented
- `core/AigentikSettings.kt` — copied verbatim from aigentik-android; SharedPreferences wrapper for all agent config
- `core/PhoneNormalizer.kt` — copied verbatim; E.164 normalization via PhoneNumberUtils
- `core/ChannelManager.kt` — copied verbatim; SMS/GVOICE/EMAIL enable-disable with persistence
- `core/MessageDeduplicator.kt` — copied verbatim (v1.2); replaces hand-written sms/ version; has `markSent`, `wasSentRecently`, `isNew`, `fingerprint` as separate methods
- `sms/NotificationReplyRouter.kt` — replaced with aigentik-android v1.1; REPLY_KEYS lookup table for Samsung vs Google Messages, dual messageId+sbnKey ConcurrentHashMap, proven `send(ctx, 0, intent)` call
- `sms/AigentikNotificationListener.kt` — rewrote to match aigentik-android NotificationAdapter v1.5 exactly; `onListenerConnected()` sets `NotificationReplyRouter.appContext`; ChannelManager gate; `resolveSender()` with PhoneNormalizer; ALWAYS registers with router even if duplicate
- `sms/MessageDeduplicator.kt` — emptied (replaced by core/ version)
- `agent/AgentMessageEngine.kt` — converted from `@Single class` to `object` to match aigentik-android pattern; all engines are objects not Koin-managed
- `system/ConnectionWatchdog.kt` — stubbed (OAuth checks TODO Stage 2)
- `core/AigentikService.kt` — updated to `AigentikSettings.init()` first, then `ChannelManager.loadFromSettings()`, then `ConnectionWatchdog.start()`

### Architecture decision
All agent-layer engines use `object` singleton pattern (aigentik-android style), NOT Koin `@Single`.
Reason: `NotificationListenerService` is OS-instantiated and can't use `by inject()` cleanly at bind time.
Koin `@Single` remains for UI-layer ViewModels and Room/data classes only.

### Next Session: Start Here — Stage 1
Read files from aigentik-android:
- `core/ContactEngine.kt` → copy to `com.aigentik.app.core`, add `Contact` entity to `AppRoomDatabase` (db version 2)
- `core/AdminAuthManager.kt` → copy to `com.aigentik.app.core`
- `core/MessageEngine.kt` → copy to `com.aigentik.app.core`, replace `AiEngine` calls with `AgentLLMFacade`
- `ai/AiEngine.kt` → read as reference, create `com.aigentik.app.ai.AgentLLMFacade` wrapping `SmolLMManager`
- Update `AigentikService.kt` → init ContactEngine, load model, call MessageEngine.configure()
- Replace `AgentMessageEngine.onMessageReceived()` call in `AigentikNotificationListener` with `MessageEngine.onMessageReceived()`
- Test: send SMS → get AI reply back

---

## 2026-03-12 — Session 3: Stage 1 Complete

### Implemented

- `core/ContactEntity.kt` — Room entity for contact intelligence (copied from aigentik-android)
- `core/ContactDao.kt` — Room DAO for contacts (synchronous queries, IO-thread callers)
- `core/ContactDatabase.kt` — standalone Room database for contacts (separate from AppRoomDatabase)
- `core/ContactEngine.kt` — full contact intelligence: findContact, findOrCreate, syncAndroidContacts, Room persistence, JSON migration path, CopyOnWriteArrayList thread safety
- `auth/AdminAuthManager.kt` (new package) — remote admin auth via SMS; SHA-256 password hashing, 30-min session TTL, destructive keyword detection
- `ai/AgentLLMFacade.kt` — wraps SmolLM directly (NOT SmolLMManager); storeChats=false for stateless generation; generateSmsReply, generateChatReply, interpretCommand, parseSimpleCommandPublic; regex-based fast path + LLM fallback; ReentrantLock for JNI thread safety; warmUp on load
- `core/MessageEngine.kt` — Stage 1 SMS/RCS engine; messageMutex serializes all handlers; admin auth via AdminAuthManager; fast-path bypasses LLM for known commands; ContactEngine for sender context; AgentLLMFacade for AI replies; NotificationReplyRouter for inline replies; email actions stubbed for Stage 2
- `core/AigentikService.kt` — updated: initAgentEngines() launches ContactEngine.init() on IO, configures MessageEngine, loads AgentLLMFacade model if modelPath is set
- `sms/AigentikNotificationListener.kt` — updated: routes SMS/RCS to MessageEngine instead of AgentMessageEngine stub; ContactEngine name lookup for senderName and sender resolution

### Architecture

- `AgentLLMFacade` is an `object` (not Koin) with its own `SmolLM` instance — independent from SmolLMManager (chat UI). Two model instances may coexist if chat UI is also active. Known Stage 2 issue: implement model sharing.
- `ContactDatabase` is a SEPARATE Room database from `AppRoomDatabase` (chat/tasks). No version conflict.
- Admin commands via SMS: format `Admin: YourName\nPassword: xxxx\n<command>`. Session lasts 30 min.

### Testing — What You Need

1. **Model path**: `AigentikSettings.modelPath` must be set. After downloading a model via SmolChat UI, find the path and set it. The agent uses this path for `AgentLLMFacade.loadModel()`. (Settings UI for this is Stage 2.)
2. **Admin number**: Set `AigentikSettings.adminNumber` to your phone number (the one you'll SMS from).
3. **Owner name**: Set `AigentikSettings.ownerName` to your name.
4. **Notification access**: Must be granted (Settings → Notification Access → Aigentik).

### Test Scenarios

1. **Cold start**: Launch app → check `adb logcat -s AigentikService,MessageEngine,AgentLLMFacade` → should see ContactEngine ready, MessageEngine configured, model loading
2. **SMS from unknown number**: Should get AI reply via inline reply: "Hi there, Aigentik here..."
3. **SMS from admin number**: Should route to handleAdminCommand; try "status" → should reply with AI/contact/channel status
4. **Admin via SMS**: Send `Admin: YourName\nPassword: xxxx\nstatus` → auth + command in one message

### Session 3 Addendum: Model Path Auto-Bridge

- `AigentikService.resolveModelPath()` — checks `AigentikSettings.modelPath` first; if blank, queries `AppDB.getModelsList()` via `GlobalContext.get().get<AppDB>()` and uses first downloaded model's path. Caches result in `AigentikSettings.modelPath` for future restarts. **No manual path setup required** — just download a model through the SmolChat UI.

### Known Gaps (Stage 2)

- `AigentikSettings.modelPath` not set automatically from SmolChat's model selection — needs Settings UI or manual set
- Email actions all return "not available — Stage 2" responses
- ownerNotifier only logs to logcat; no push notification to owner's screen yet
- Conversation history not persisted between sessions (no ConversationHistoryDatabase)
- `SmolLM.storeChats=false` behavior relies on messages list clearing after each completion — if behavior differs, context may accumulate; needs validation

---

## 2026-03-12 — Session 4: Stage 2 Complete — Gmail OAuth + Email Pipeline

### Implemented

- `core/Message.kt` — top-level `Message` data class + nested `Channel` enum. Promoted from `MessageEngine.Message` (inner class) so `EmailMonitor` can reference `com.aigentik.app.core.Message` directly. Fields: id, channel, sender, senderName, body, subject, packageName, timestamp, threadId.
- `auth/GoogleAuthManager.kt` — copied verbatim from aigentik-android v1.8. Critical: handles `UserRecoverableAuthException` for restricted Gmail scopes — stores resolution `Intent` in `pendingScopeIntent`, calls `scopeResolutionListener` for Settings UI to launch consent dialog.
- `email/GmailApiClient.kt` — copied verbatim v1.6. Full Gmail REST client via OkHttp: listUnread, getFullEmail, sendEmail, replyToEmail, replyToGoogleVoiceText, markAsRead, deleteAllMatching (batchModify), countUnreadBySender. Recursive MIME body extractor (HTML OOM safety). GVoice parse helpers.
- `email/GmailHistoryClient.kt` — copied verbatim v1.2. Delta-based history fetch (History API). `primeHistoryId()` returns `PrimeResult` enum (ALREADY_STORED, PRIMED_FROM_API, NO_TOKEN, API_ERROR, NETWORK_ERROR). Persistent historyId in SharedPreferences ("gmail_history").
- `email/EmailRouter.kt` — copied verbatim v2.2. ConcurrentHashMap keyed by sender (phone digits / email lowercase). `routeReply()` does O(1) GVoice and email lookup. `sendEmailDirect()` for admin send-email command. `notifyOwner()` sends status email to self.
- `email/EmailMonitor.kt` — copied verbatim v4.3. AtomicBoolean `isProcessing.compareAndSet()` prevents duplicate fetch from notification bursts. Primary path: History API delta. Fallback: `listUnread(maxResults=3)`. `processEmail()` routes GVoice vs regular email. Builds `com.aigentik.app.core.Message` objects for `MessageEngine.onMessageReceived()`.
- `core/MessageEngine.kt` — updated to v2.0. Removed inner `Message`/`Channel` — now uses top-level `Message`. Email/GVoice channel reply via `EmailRouter.routeReply()`. Admin `check_email` → `GmailApiClient.countUnreadBySender()`. Admin `send_email` → `EmailRouter.sendEmailDirect()`. Status includes Gmail sign-in state.
- `sms/AigentikNotificationListener.kt` — updated to v3.0. Gmail notifications now route to `EmailMonitor.onGmailNotification(applicationContext)` instead of stub. SMS builder updated to use top-level `Message(...)` and `Message.Channel.NOTIFICATION`.
- `core/AigentikService.kt` — updated: `GoogleAuthManager.initFromStoredAccount()` called in `onCreate()`. `EmailRouter.init()` and `EmailMonitor.init()` called before `MessageEngine.configure()`. `GmailHistoryClient.primeHistoryId()` launched on IO thread at startup.
- `app/build.gradle.kts` — added `com.google.code.gson:gson:2.10.1` (explicit Gson dep for GmailApiClient).

### Full Pipeline (Stage 2)

```
Gmail notification
  → AigentikNotificationListener.onNotificationPosted()
  → EmailMonitor.onGmailNotification()
  → GmailHistoryClient.getNewInboxMessageIds() [or listUnread fallback]
  → GmailApiClient.getFullEmail()
  → processEmail() → GVoice? or regular?
  → EmailRouter.storeGVoiceContext() / storeEmailContext()
  → MessageEngine.onMessageReceived(Message(channel=EMAIL))
  → handlePublicMessage()
  → AgentLLMFacade.generateSmsReply()
  → EmailRouter.routeReply(sender, reply)
  → GmailApiClient.replyToGoogleVoiceText() / replyToEmail()
```

### Architecture Notes

- `Message` is top-level in `com.aigentik.app.core.Message` — AigentikNotificationListener imports it directly.
- `EmailRouter` and `EmailMonitor` are `object` singletons (not Koin) — consistent with all agent-layer engines.
- `GoogleAuthManager.initFromStoredAccount()` restores Google sign-in state on service restart — required so `getFreshToken()` succeeds without a fresh sign-in on every reboot.
- `GmailHistoryClient.primeHistoryId()` fires on every service start. If not signed in → returns `NO_TOKEN` immediately (no harm). After first sign-in and Gmail notification → stores historyId for delta-based processing.
- Two-SmolLM instances issue remains (AgentLLMFacade + SmolLMManager for chat UI). Stage 3 item.

### Testing — What You Need (Stage 2 additions)

1. **Google sign-in**: Launch app → Settings → Sign in with Google → grant Gmail permissions when prompted
2. **Gmail notification**: Receive an email → check logcat `adb logcat -s EmailMonitor,GmailHistoryClient,GmailApiClient,EmailRouter` → should see history fetch, email processing, reply sent
3. **Admin email check**: SMS "check email" from admin number → should get unread count summary back
4. **GVoice**: If using Google Voice → receive GVoice text → should route through Email pipeline and reply back via Gmail

### Known Gaps (Stage 3)

- Gmail sign-in UI not yet added to Settings screen — user must sign in manually (no UI trigger)
- `scopeResolutionListener` not wired to a SettingsActivity — `UserRecoverableAuthException` consent dialog can't auto-launch without it
- `ownerNotifier` still only logs to logcat (no push notification UI)
- Two LLM instances (AgentLLMFacade + SmolLMManager) — share model in Stage 3
- Conversation history not persisted (no ConversationHistoryDatabase)

---

## 2026-03-12 — Session 5: Stage 3 — Agent Settings UI

### Implemented

- `ui/screens/agent_settings/AgentSettingsActivity.kt` — already existed with complete implementation (Compose, sign-in launcher, Gmail scope consent launcher, scopeResolutionListener in onResume/onPause, channel toggles, status section). Fixed: replaced Java reflection for email lookup with `AigentikSettings.gmailAddress`. Added email display in the "hasPendingScope" state (user signed in but scopes not yet granted).
- `ui/screens/agent_settings/AgentSettingsViewModel.kt` — @KoinViewModel ViewModel for future use; registers with Koin but not yet wired to AgentSettingsActivity (which uses its own inline Compose state).
- `ui/screens/agent_settings/AgentSettingsScreen.kt` — standalone Compose screen composable backed by AgentSettingsViewModel; dead code for now (Activity uses its own private composable), but compiles cleanly.
- `AndroidManifest.xml` — registered `AgentSettingsActivity` (exported=false).
- `ChatMoreOptionsPopup.kt` — added `onAgentSettingsClick: () -> Unit` parameter; added "Aigentik Settings" menu item with `FeatherIcons.Shield`.
- `ChatActivity.kt` — imported `AgentSettingsActivity`; added `onAgentSettingsClick` parameter to `ChatActivityScreenUI`; wired up `Intent` to launch `AgentSettingsActivity` from the ⋮ menu; updated Preview.

### Full OAuth Flow (now completable from UI)

```
User taps ⋮ → "Aigentik Settings"
  → AgentSettingsActivity opens
  → "Sign In with Google" → signInLauncher → GoogleAuthManager.onSignInSuccess()
  → getFreshToken() fires → UserRecoverableAuthException
  → scopeResolutionListener (set in onResume) → scopeConsentLauncher.launch(pendingIntent)
  → User grants Gmail permissions in system dialog
  → scopeConsentLauncher result → GoogleAuthManager.onScopeConsentGranted()
  → GmailHistoryClient.primeHistoryId() → delta-based email processing active
```

### Settings Screen Sections

1. **Google Account** — Sign in / Sign out / Grant Gmail Permissions button (shows when needed)
2. **Agent Configuration** — Agent name, owner name, phone number (auto-saved on change to SharedPreferences)
3. **Channels** — SMS/RCS, Email, Google Voice toggles (saved immediately via ChannelManager)
4. **Status** — AI model state (AgentLLMFacade.getStateLabel()), contact count, Gmail status

## 2026-03-13 — Session 6: On-demand LLM load + idle unload

### Problem
Model only responded when app was on screen. Root cause: `generateSmsReply` /
`generateChatReply` / `interpretCommand` all had `if (!isReady()) return fallback`
— so any message arriving before the ~30-60s eager load completed silently got
the canned fallback reply. Service restarts (Samsung battery killer) made this
window repeat every reboot.

### Fix: AgentLLMFacade v2.0

**`ensureLoaded()`** — on-demand load replacing the `isReady()` guard:
- Cancels any pending idle-unload timer
- If already READY → returns immediately
- If another coroutine is already loading → all callers share one
  `CompletableDeferred<Boolean>` (one native load, N waiters unblock together)
- If NOT_LOADED → launches `doLoad()` in `facadeScope`, suspends until complete

**`scheduleIdleUnload()`** — called at the end of every generation:
- Starts a 60-second countdown coroutine
- Next `ensureLoaded()` call cancels it → model stays loaded
- After 60s with no activity → `unloadModel()` calls `smolLM.close()` → RAM freed

**`storeModelPath(path)`** — called from `AigentikService` so the facade can
reload on demand without being handed the path again.

**`unloadModel()`** — `smolLM.close()` releases native pointer + VRAM/RAM.
`state` → `NOT_LOADED`; next message triggers a fresh load.

### AigentikService changes
- Calls `AgentLLMFacade.storeModelPath(path)` before `loadModel()` (path now
  cached independently of the eager load)
- `updateNotification(text)` helper — notification now says "ready" vs "model
  pending" so the user can see load state in the status bar
- Eager warm-up load on service start still happens — first message pays
  ~0s latency if it arrives after the warm-up; otherwise `ensureLoaded()`
  blocks the reply coroutine until load finishes (no silent fallback)

### Timing example (Samsung S25, Qwen 0.5B Q4)
```
Notification received  → ensureLoaded() called
  model NOT_LOADED     → doLoad() starts (~30s)
  reply coroutine      → suspends on deferred.await()
  load completes       → deferred.complete(true)
  reply coroutine      → unblocks, generates reply (~8s)
  scheduleIdleUnload() → 60s timer starts
  reply sent           ← total ~38s first message
  [60s idle]           → unloadModel() called, RAM freed
  next message         → repeat
```

### Bug Fixes (same session, 2026-03-13)

- `GoogleAuthManager.kt:120` — `Account(account.email, ...)` failed with `String?` vs `String`. Fixed: `account.email ?: ""`.
- `res/values/strings.xml` — `label_device_ram` used unnamed `%.2f` printf placeholders triggering `mergeDebugResources` warning. Fixed: added `formatted="false"` attribute.

### Known Gaps (Stage 4)

- Admin password management not yet in settings UI (still set only via `AdminAuthManager.hashPassword` programmatically)
- `AgentSettingsViewModel` / `AgentSettingsScreen` are dead code — either wire them up or remove in Stage 4
- Two LLM instances (AgentLLMFacade + SmolLMManager) — share model
- Conversation history not persisted
- No owner push notification (ownerNotifier still logs only)

### Next Session: Start Here — Stage 4

Options (pick highest-impact for grant demo):
1. **Phase I Research: Benchmark Infrastructure** — `BenchmarkRunner.kt`, `MetricsStore.kt`, `LatencyTracer.kt` per `EVALUATION_PROTOCOL.md`
2. **Phase I Research: Action Policy Engine** — `ActionSchema.kt`, `RiskScorer.kt`, `ActionPolicyEngine.kt` extending `DestructiveActionGuard`
3. **Admin password UI** — add password fields to AgentSettingsActivity (minor)
4. **Model sharing** — route AgentLLMFacade through SmolLMManager to eliminate two JNI instances

Priority order per CLAUDE.md: Benchmark Infrastructure → Action Policy Engine.

---
