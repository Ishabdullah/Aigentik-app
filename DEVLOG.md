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

### Known Gaps (Stage 2)

- `AigentikSettings.modelPath` not set automatically from SmolChat's model selection — needs Settings UI or manual set
- Email actions all return "not available — Stage 2" responses
- ownerNotifier only logs to logcat; no push notification to owner's screen yet
- Conversation history not persisted between sessions (no ConversationHistoryDatabase)
- `SmolLM.storeChats=false` behavior relies on messages list clearing after each completion — if behavior differs, context may accumulate; needs validation

---
