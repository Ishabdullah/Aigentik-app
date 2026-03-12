# CLAUDE.md — Aigentik Project Context and Roadmap

This file is the primary context document for AI-assisted development on this project. Read it before making any changes.

---

## What This Project Is

**Aigentik** is a privacy-preserving on-device AI agent framework for Android. It runs local LLM inference (llama.cpp / GGUF models) and uses that inference to autonomously handle communications: Gmail, SMS/RCS, and calendar — with a policy engine that enforces safety constraints before any action executes.

**The goal is an NSF SBIR/STTR grant.** The project must evolve from a working app into a demonstrable R&D platform with benchmarks, safety architecture, and peer-credible documentation. Every engineering decision should be made with that dual lens: **does this make the product work better?** and **does this make the research more rigorous?**

**This is a fork of [SmolChat-Android by Shubham Panchal](https://github.com/shubham0204/SmolChat-Android).** The base was taken as a starting point for Jetpack Compose UI and llama.cpp JNI bindings. The research and agent architecture on top of it is original work.

---

## Project Structure

```
aigentik-app/
├── app/                         # Main Android app (Jetpack Compose)
│   └── src/main/java/com/aigentik/app/
│       ├── data/                # Room database, data models
│       ├── llm/                 # LLM inference (SmolLM JNI wrapper)
│       ├── email/               # Gmail API integration
│       ├── calendar/            # Google Calendar + system calendar
│       ├── sms/                 # SMS/RCS via NotificationListenerService
│       ├── ui/                  # Jetpack Compose screens
│       └── prism4j/             # Code syntax highlighting
├── smollm/                      # Android library: JNI bindings for llama.cpp
├── smolvectordb/                # Android library: on-device vector search
├── hf-model-hub-api/            # Kotlin/JVM library: Hugging Face Hub API client
├── llama.cpp/                   # Git submodule (llama.cpp inference engine)
├── .github/workflows/build.yml  # CI/CD: build + sign APK on push to main
└── [Research docs]              # See below
```

### Research Documentation Files
- `TECHNICAL_INNOVATION.md` — Core research claims and open questions
- `RESEARCH_PLAN.md` — Phase I objectives with measurable success criteria
- `EVALUATION_PROTOCOL.md` — Benchmark methodology and metrics schema
- `BASELINE_COMPARISON.md` — How Aigentik differs from existing solutions
- `THREAT_MODEL.md` — Security threat analysis for autonomous agent
- `COMMERCIALIZATION_HYPOTHESES.md` — Market hypotheses and business model

---

## Build System

- **Gradle:** 8.9 wrapper + AGP 8.13.0
- **Kotlin:** 2.0.0
- **NDK:** r27b (27.2.12479018) — required for llama.cpp
- **CMake:** 3.22.1
- **Java:** 17
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35 (Android 15)

### Critical Build Notes

1. **Google Services plugin must be declared in root `build.gradle.kts`** with explicit version:
   ```kotlin
   id("com.google.gms.google-services") version "4.4.2" apply false
   ```
   Without this, `app/build.gradle.kts` cannot apply the plugin and the build fails.

2. **NDK is required.** The `smollm` and `smolvectordb` modules compile C++ via CMake. Without NDK r27b, the build fails at native compilation.

3. **llama.cpp is a git submodule.** Always checkout with `--recurse-submodules`. CI does this automatically.

4. **Fixed debug keystore for OAuth.** The GitHub Actions workflow uses a base64-encoded keystore (secret: `DEBUG_KEYSTORE_BASE64`) to ensure consistent SHA-1 certificate hash across builds. This SHA-1 (`e67661285f6c279d1434c5662c1e174e32679d80`) is registered in the Google Cloud Console and `google-services.json` for OAuth2 to work. **Never regenerate the debug keystore unless you also update the OAuth client in Google Cloud Console.**

5. **smolvectordb includes `:smolvectordb` at the TOP of `settings.gradle.kts`** before the `pluginManagement` block. This is intentional for module resolution order.

### To Build Locally
```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/your-org/aigentik-app

# Build debug APK
./gradlew assembleDebug

# Build with NDK explicitly
./gradlew assembleDebug -Pandroid.ndkVersion=27.2.12479018
```

---

## Credentials and OAuth

**Package name:** `com.aigentik.app`
**Firebase project:** `aigentik-android` (project #630924077353)
**OAuth client ID (Android):** `630924077353-gmv67c8n0lad1q5u9q6v8t41sf79l8uv.apps.googleusercontent.com`
**Certificate SHA-1 (debug keystore):** `e67661285f6c279d1434c5662c1e174e32679d80`

These credentials **must match** the aigentik-android project credentials exactly. The `google-services.json` file in `app/` is shared between both projects (same Firebase project, same package name).

If you need to add a new OAuth scope or change the package name:
1. Update the Google Cloud Console OAuth client
2. Update `google-services.json`
3. If changing package name: also update `namespace` and `applicationId` in `app/build.gradle.kts` and all `AndroidManifest.xml` package references

---

## Development Roadmap

### Current State (v1.0.0)
- [x] Jetpack Compose UI with chat interface
- [x] llama.cpp inference via JNI (smollm module)
- [x] GGUF model download from Hugging Face Hub
- [x] Room database for conversation persistence
- [x] Gmail OAuth2 integration
- [x] SMS/RCS via NotificationListenerService
- [x] Calendar read/write
- [x] GitHub Actions CI/CD with fixed keystore
- [x] on-device vector database (smolvectordb)

### Phase I Research Additions (Current Priority)

These are the core research modules that make the project grant-fundable. Work in this order:

#### 1. Benchmark Infrastructure (Highest Priority)
Add to `app/src/main/java/com/aigentik/app/benchmark/`:
- `BenchmarkRunner.kt` — drives agent loop with scripted task corpus
- `ExperimentConfig.kt` — data class for experiment parameters
- `MetricsStore.kt` — Room entity + DAO for per-task metrics
- `BatteryStatsCollector.kt` — BatteryManager API wrapper
- `ThermalStateCollector.kt` — PowerManager thermal status wrapper
- `LatencyTracer.kt` — timestamp capture at key pipeline stages
- `MetricsExporter.kt` — CSV/JSON export for experiment results

See `EVALUATION_PROTOCOL.md` for the exact schema.

#### 2. Action Policy Engine
Add to `app/src/main/java/com/aigentik/app/agent/`:
- `ActionSchema.kt` — sealed class hierarchy for all action types
- `ActionSchemaValidator.kt` — validates model output against schema
- `RiskScorer.kt` — maps action type → risk tier
- `ActionPolicyEngine.kt` — combines schema + risk + confidence → decision
- `ApprovalWorkflow.kt` — suspends action, shows confirmation UI
- `DecisionTraceLogger.kt` — writes trace to encrypted Room store

The policy engine must sit between model output and action execution — no action executes without passing through it.

#### 3. Adaptive Model Routing
Add to `app/src/main/java/com/aigentik/app/routing/`:
- `TaskClassifier.kt` — lightweight classifier: input → task type
- `ModelProfile.kt` — data class: model tier → (latency_p50, latency_p90, accuracy, ram_mb)
- `DeviceStateReader.kt` — reads battery %, thermal status, available RAM
- `ModelRouter.kt` — combines task type + device state → model tier selection
- `RoutingLogger.kt` — logs every routing decision for offline analysis

#### 4. Energy-Adaptive Scheduler
Add to `app/src/main/java/com/aigentik/app/scheduler/`:
- `InferenceScheduler.kt` — queues inference requests, applies energy policy
- `ThermalPolicy.kt` — maps thermal state → max context length, quantization constraint
- `BatteryPolicy.kt` — defers non-urgent tasks below threshold battery %
- `ChargingWindowDetector.kt` — detects charging state for batch processing

#### 5. On-Device Memory System
Add to `app/src/main/java/com/aigentik/app/memory/`:
- `MemoryStore.kt` — Room entity for memory entries with decay score
- `MemorySummarizer.kt` — uses local model to compress older context
- `MemoryRetriever.kt` — wraps smolvectordb for similarity search
- `MemoryDecayEngine.kt` — ages and prunes low-value memories
- `ContactPersonalization.kt` — per-contact preference profiles

### Phase II (Post-Grant, 6–18 months)

- Multi-device sync via encrypted device-owned keypair (no cloud)
- Prompt injection detection as pre-inference classifier
- 60-day user study with target market (attorneys, healthcare professionals)
- Enterprise MDM configuration protocol
- HIPAA compliance review and documentation
- App Store / Play Store submission

---

## Code Style and Architecture Decisions

- **Dependency injection:** Koin with `@Module` / `@Single` / `@Factory` annotations. Use KSP processor, not KAPT.
- **Async:** Kotlin coroutines. Use `viewModelScope` for UI-linked work, `lifecycleScope` for service-linked work.
- **Database:** Room with KSP annotation processing. No raw SQL except where needed for performance.
- **Navigation:** Jetpack Compose Navigation with typed routes (kotlinx.serialization).
- **No mocking in tests that touch Room or inference.** Real database and real model required. See `EVALUATION_PROTOCOL.md`.
- **No cloud inference calls.** All LLM inference must go through the local smollm module. Never add a remote inference endpoint.
- **Policy engine is not optional.** Any new action capability must be added to the action schema and go through `ActionPolicyEngine` before execution.

---

## What NOT to Do

- Do not add remote inference (OpenAI API, Anthropic API, Gemini API) as a fallback or primary inference path. The offline-only constraint is both a research requirement and a commercial differentiator.
- Do not skip the policy engine for "simple" actions. Every agent-triggered action that modifies external state (sends messages, writes calendar, etc.) must be validated.
- Do not store message content in plaintext outside of app-private storage. See `THREAT_MODEL.md`.
- Do not regenerate the debug keystore without updating Google Cloud Console OAuth registration.
- Do not refactor the SmolChat base code without understanding what research functionality depends on it. The smollm module API is stable.

---

## Grant Context

**Target:** NSF SBIR Phase I
**Core claim:** On-device agent framework for reliable, safe, energy-aware mobile autonomy without cloud dependency.
**Differentiators:** Privacy model, typed safety layer, energy-adaptive inference, offline reliability.
**Evidence required:** Benchmark results from real Android hardware, published evaluation methodology, documented threat model, customer discovery (commercialization section).

The research documentation files in this repo are as important as the code. Keep them up to date as the implementation evolves.

---

## Contact and Ownership

- **Firebase / Google Cloud project:** aigentik-android
- **Package name:** com.aigentik.app
- **Credentials match:** Both aigentik-app and aigentik-android use the same Firebase project and OAuth client. Do not split them without updating both repos.

---

## Fusion Strategy — READ THIS BEFORE TOUCHING AGENT CODE

**aigentik-app is a fusion of two projects:**
1. **SmolChat-Android** (base) — Jetpack Compose UI, llama.cpp JNI via `smollm` module, GGUF model download, Room DB for chats. Keep this untouched.
2. **aigentik-android** (agent layer) — production SMS/RCS via NotificationListenerService inline reply, Gmail OAuth2 + REST API, Google Voice routing, MessageEngine routing hub, ContactEngine, RuleEngine, AdminAuth, ChannelManager.

**The source files for the agent layer already exist and are proven.** Do not rewrite them. Copy from:
`/data/data/com.termux/files/home/aigentik-android/app/src/main/java/com/aigentik/app/`

**Adaptation work when copying:**
- Both projects use `com.aigentik.app` as base package — package declarations rarely need changing
- Convert `object` singletons to `@Single class` for Koin where the class needs injection
- Replace `AiEngine` calls with `AgentLLMFacade` (wrapper around `SmolLMManager`) in `MessageEngine`
- Merge Room entities into single `AppRoomDatabase` (don't create separate databases)
- `ChatDatabase` references → `AppDB.addAssistantMessage()`

**Detailed plan:** See `aigentik-fuse.md` (bottom section: "Staged Implementation Plan")
**Session log:** See `DEVLOG.md` — read this first to know where the last session left off

---

## Current Implementation State (updated 2026-03-12)

### What is working on device
- Jetpack Compose UI, model download, llama.cpp inference — fully working (SmolChat base)
- `AigentikService` foreground service — running, shows persistent notification, keeps process alive on Samsung
- `BootReceiver` — restarts service on boot/package-replace
- `AigentikNotificationListener` — confirmed binding (`onListenerConnected` fires), receives SMS/RCS notifications from Google Messages and Samsung Messages
- Battery optimization exemption — requested on first launch

### What is NOT yet working
- SMS/RCS → AI reply pipeline: notifications received but not yet routed to LLM and replied
- Gmail: OAuth wired but `GmailApiClient`/`EmailMonitor` not yet ported
- Admin commands, RuleEngine, ContactEngine — not yet ported

### Current package structure for agent files
```
com.aigentik.app.core/       — AigentikSettings, PhoneNormalizer, ChannelManager,
                                MessageDeduplicator, AigentikService (foreground service)
com.aigentik.app.sms/        — AigentikNotificationListener, NotificationReplyRouter,
                                SMSService (read-only Telephony queries, keep)
com.aigentik.app.agent/      — AgentMessageEngine (stub, replaced by MessageEngine in Stage 1)
com.aigentik.app.system/     — BootReceiver, ConnectionWatchdog
com.aigentik.app.ai/         — AgentLLMFacade (Stage 1, wraps SmolLMManager)
com.aigentik.app.email/      — Gmail files (Stage 2)
com.aigentik.app.auth/       — GoogleAuthManager (Stage 2)
```

### How to pick up in a fresh session
1. Read `DEVLOG.md` — last entry tells you exactly where to start
2. Read `aigentik-fuse.md` bottom section — check off completed items, find next unchecked item
3. Source files to copy are in `/data/data/com.termux/files/home/aigentik-android/`
4. Build happens in GitHub Actions (CI) — do NOT run `./gradlew` locally
5. Push with SSH: `git push origin main`
6. After pushing, test with: `adb logcat -s AigentikNotifListener AigentikService NotificationAdapter`

### Key architectural rules (in addition to rules above)
- **AigentikSettings.init(context) must be called first** in `AigentikService.onCreate()` before any engine that reads settings
- **MessageEngine.configure() must be called BEFORE EmailMonitor.init()** — if a Gmail notification arrives between them, it processes with null wakeLock (Samsung throttles inference to 5+ min)
- **NotificationReplyRouter.appContext** must be set in `onListenerConnected()` — this is what `NotificationAdapter` does on bind. Without it, `PendingIntent.send()` fails on Android 13+
- **Never add remote inference** — all LLM calls go through `SmolLMManager` in the `smollm` module
- **All agent actions go through policy engine** — DestructiveActionGuard now, full ActionPolicyEngine in Phase I research
