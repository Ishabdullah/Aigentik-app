# Aigentik Project Log

**Format:** Date — Entry type — Description
**Entry types:** `[BUILD]` `[ARCH]` `[RESEARCH]` `[BUG]` `[FEATURE]` `[INFRA]` `[DOCS]` `[DECISION]`

---

## 2026-03-12

**[INFRA] Fixed GitHub Actions build failure — missing Google Services plugin version**

Root cause: `app/build.gradle.kts` applies `id("com.google.gms.google-services")` but the root `build.gradle.kts` did not declare the plugin with a version number. Gradle requires all plugins applied in subprojects to be declared with a version in the root `plugins {}` block when using the Plugin DSL.

Fix: Added `id("com.google.gms.google-services") version "4.4.2" apply false` to root `build.gradle.kts`.

Error was:
```
Plugin [id: 'com.google.gms.google-services'] was not found in any of the following sources:
- Plugin Repositories (plugin dependency must include a version number for this source)
```

Build now has all required plugin declarations:
- `com.android.application` (via AGP 8.13.0)
- `org.jetbrains.kotlin.android` (2.0.0)
- `org.jetbrains.kotlin.plugin.compose` (2.0.0)
- `com.android.library` (via AGP 8.13.0)
- `com.google.devtools.ksp` (2.0.0-1.0.24)
- `org.jetbrains.kotlin.jvm` (2.0.0)
- `kotlin.plugin.serialization` (2.1.0)
- `com.google.gms.google-services` (4.4.2) ← added

---

**[INFRA] Verified credentials alignment with aigentik-android**

Both projects use identical `google-services.json`:
- Firebase project: `aigentik-android` (project #630924077353)
- Package name: `com.aigentik.app`
- OAuth client ID (Android): `630924077353-gmv67c8n0lad1q5u9q6v8t41sf79l8uv.apps.googleusercontent.com`
- Certificate SHA-1: `e67661285f6c279d1434c5662c1e174e32679d80`

The SHA-1 is the fingerprint of the fixed debug keystore stored as `DEBUG_KEYSTORE_BASE64` in GitHub Actions secrets. This is intentional — it ensures consistent OAuth2 behavior across all CI builds.

---

**[DOCS] Added research documentation suite for NSF SBIR positioning**

Created:
- `TECHNICAL_INNOVATION.md` — 5 core research challenges with technical risk statements
- `RESEARCH_PLAN.md` — Phase I objectives with measurable success criteria
- `EVALUATION_PROTOCOL.md` — Benchmark methodology, metrics schema, experiment designs
- `BASELINE_COMPARISON.md` — Quantified comparison to cloud AI, offline chat apps, cloud agents
- `THREAT_MODEL.md` — Attack scenarios and mitigations for autonomous agent capabilities
- `COMMERCIALIZATION_HYPOTHESES.md` — 5 testable market hypotheses and revenue model

---

**[DOCS] Added CLAUDE.md — project context for AI-assisted development**

Covers: project purpose, build system notes, credential alignment, development roadmap, research module priority order, architecture decisions, what not to do, grant context.

---

**[DECISION] Project reframing for NSF SBIR**

Decision: Reframe project from "offline Android AI assistant" to "privacy-preserving on-device agent framework for reliable mobile autonomy under resource constraints."

Rationale: NSF SBIR funds technology innovation with high technical risk, not product polish. The core fundable innovation is the combination of: adaptive model routing, typed action safety layer, energy-aware inference, and offline-capable agent loop — none of which exist in a single open or commercial product.

Phase I research objectives defined:
1. Adaptive on-device model routing engine
2. Safe autonomous message handling with policy verification
3. Energy and latency performance benchmarks on consumer Android hardware

---

**[ARCH] Identified startup race condition (known issue, not yet fixed)**

Current bug: Gmail commands and SMS events arriving before inference service initialization completes are silently dropped. This is documented in `TECHNICAL_INNOVATION.md` under "Reliable Startup Sequencing."

Planned fix: Timestamped command queue with ordered replay after initialization completes, plus health state machine gating action execution to initialization stage.

Priority: Medium (after benchmark infrastructure and policy engine).

---

**[BUILD] Fixed fourth build failure — Kotlin compilation errors in SMS package**

Three files had API usage errors. Root cause: SMS code was written against non-existent or private Android SDK symbols.

**AigentikNotificationListener.kt:**
- `notification.isOngoing` → `(notification.flags and Notification.FLAG_ONGOING_EVENT) == 0` — `isOngoing` is not a public property; `FLAG_ONGOING_EVENT` is the correct flag
- `action.getRemoteInput()` → `action.remoteInputs?.firstOrNull()` — the public API is the `remoteInputs: Array<RemoteInput>?` property, not a `getRemoteInput()` method
- `remoteInput.putResultsFromInput(...)` + bad `pendingIntent.send()` → replaced with `RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)` + `pendingIntent.send(this, 0, intent)`
- `context?.contentResolver` / `context?.packageName` inside `NotificationListenerService` → use `contentResolver` / `packageName` directly (the service IS the context)

**SMSReceiver.kt:**
- `intent.getParcelableArrayListExtra(Telephony.Intents.EXTRA_SMS_MESSAGES, SMSMessage::class.java)` → `Telephony.Sms.Intents.getMessagesFromIntent(intent)` — SMS intents carry raw PDU data as `android.telephony.SmsMessage`, not the app's `SMSMessage` data class. The `Telephony.Intents` class also doesn't exist; it's `Telephony.Sms.Intents`
- `sms.originatingAddress`, `sms.messageBody`, `sms.timestampMillis` now work correctly as these are properties of `android.telephony.SmsMessage`
- Added `threadId = 0L` (unknown at receive time, resolved later via content provider)

**SMSService.kt:**
- `Telephony.Threads.UNREAD_COUNT` → `"unread_count"` raw string constant — this symbol is unavailable in the public SDK; the column name is stable
- `List<PendingIntent>` → `ArrayList<PendingIntent>` for `SmsManager.sendMultipartTextMessage()` which requires `ArrayList` not `List`
- `Telephony.Participants` → replaced with query to `content://mms-sms/canonical-addresses` — `Telephony.Participants` is a hidden internal API that is not in the public SDK

---

**[BUILD] Fixed third build failure — duplicate launcher icon resources**

Root cause: Each `mipmap-*` directory contained both `.png` and `.webp` versions of `ic_launcher`, `ic_launcher_foreground`, and `ic_launcher_round`. Android resource merger treats `.png` and `.webp` with the same base name as duplicate resources and fails.

This occurred because `.webp` icons were added without removing the original `.png` files (probably from "Update icons" commit).

Fix: Added a `removeDuplicateLauncherIcons` Gradle task to `app/build.gradle.kts` that deletes `.png` files when a matching `.webp` exists in the same mipmap directory, wired to run before all `MergeResources` tasks via `afterEvaluate`.

Permanent cleanup needed: Run `git rm app/src/main/res/mipmap-*/ic_launcher*.png` to permanently remove the PNGs from the repository and avoid the runtime deletion.

---

**[BUILD] Fixed KSP/Kotlin version mismatch warning**

`kotlin("plugin.serialization")` was pinned to version `2.1.0` across all modules (`app/`, `hf-model-hub-api/`, root) while the main Kotlin version in `libs.versions.toml` is `2.0.0`. KSP `2.0.0-1.0.24` is only compatible with Kotlin 2.0.x. The 2.1.0 serialization plugin caused repeated warnings.

Fix: Downgraded serialization plugin to `2.0.0` in all three `build.gradle.kts` files to match the Kotlin version.

---

**[BUILD] Fixed second build failure — Gradle version mismatch in CI workflow**

Root cause: AGP 8.13.0 requires Gradle 8.13+. The `gradle-wrapper.properties` already specifies `gradle-8.13-bin.zip`, but `.github/workflows/build.yml` overrode it with `gradle-version: "8.9"` via `gradle/actions/setup-gradle@v3`. This caused Gradle 8.9 to be used instead of the wrapper version.

Fix:
1. Removed `gradle-version: "8.9"` from the `setup-gradle` step — now uses the wrapper's version (8.13) automatically
2. Changed `gradle assembleDebug` → `./gradlew assembleDebug` — standard practice; uses the project's Gradle wrapper binary directly

Error was:
```
Minimum supported Gradle version is 8.13. Current version is 8.9.
```

---

## Template for Future Entries

```
## YYYY-MM-DD

**[TYPE] Short summary**

Detailed description of what changed, why, and any decisions made.
Include: what problem was solved, what approach was chosen, what alternatives were considered.

If a bug: root cause, fix applied, how to verify.
If a decision: options considered, decision made, rationale, who made it.
If a feature: what was added, what it enables, what remains to do.
```

---

## Planned Upcoming Work

- [ ] Add `benchmark/` package: BenchmarkRunner, MetricsStore, BatteryStatsCollector, ThermalStateCollector, LatencyTracer, MetricsExporter
- [ ] Add `agent/` package: ActionSchema, ActionSchemaValidator, RiskScorer, ActionPolicyEngine, ApprovalWorkflow, DecisionTraceLogger
- [ ] Add `routing/` package: TaskClassifier, ModelProfile, DeviceStateReader, ModelRouter, RoutingLogger
- [ ] Add `scheduler/` package: InferenceScheduler, ThermalPolicy, BatteryPolicy, ChargingWindowDetector
- [ ] Add `memory/` package: MemoryStore, MemorySummarizer, MemoryRetriever, MemoryDecayEngine, ContactPersonalization
- [ ] Fix startup race: implement command queue with replay after initialization
- [ ] Run Experiment Set 1 (baseline model tier comparison) on physical device
- [ ] Customer discovery: 30 interviews with Segment 1 target users (attorneys, healthcare professionals)
