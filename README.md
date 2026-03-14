<img src="resources/app_icon/icon.png" alt="app icon" width="256"/>

# Aigentik - On-Device AI Assistant with Email, Calendar & SMS Integration

<table>
<tr>
<td>
<img src="resources/app_screenshots/phone/1.png" alt="app_img_01">
</td>
<td>
<img src="resources/app_screenshots/phone/2.png" alt="app_img_02">
</td>
<td>
<img src="resources/app_screenshots/phone/3.png" alt="app_img_03">
</td>
</tr>
<tr>
<td>
<img src="resources/app_screenshots/phone/4.png" alt="app_img_04">
</td>
<td>
<img src="resources/app_screenshots/phone/5.png" alt="app_img_05">
</td>
<td>
<img src="resources/app_screenshots/phone/6.png" alt="app_img_06">
</td>
</tr>
</table>

## Features

### On-Device LLM Inference
- Runs GGUF models locally via llama.cpp — no cloud inference, no data leaves the device
- On-demand load + 5-minute idle unload: model loads on first message, unloads after inactivity (frees RAM)
- PBKDF2WithHmacSHA256 password hashing for remote admin auth (100k iterations, random salt)

### Autonomous Agent Pipeline
- **SMS/RCS auto-reply** — intercepts notifications from Google Messages, Samsung Messages, Verizon Messages, AT&T Messages, and other carrier SMS apps; replies via inline notification action
- **Gmail auto-reply** — triggered by Gmail notifications; History API delta fetch; GVoice SMS via Gmail routing
- **Action Policy Engine** — typed action schema, risk scoring, confidence gating; blocks unsafe actions before execution; every action gets an audit trail
- **Destructive command confirmation** — admin commands containing delete/wipe/broadcast keywords require explicit "confirm: ..." reply before execution
- **Admin auth** — SMS-based remote admin with 30-minute session management and PBKDF2-hashed password

### Agent Safety
- Policy engine sits between LLM output and action execution — no action executes without passing through it
- Risk tiers: LOW (status queries), MEDIUM (auto-replies), HIGH (send email, unknown actions)
- REQUIRE_APPROVAL path: medium-confidence public replies are flagged for owner review
- OAuth health watchdog: re-auth notification posted if Gmail token expires while running in background

### Research Platform (NSF SBIR Phase I)
- **Benchmark infrastructure** — `BenchmarkRunner` drives 500-task JSONL corpus; `MetricsExporter` produces `metrics.csv`, `summary.json`, `thermal_trace.csv` with latency/battery/thermal per task
- **Evaluation protocol** — per EVALUATION_PROTOCOL.md: p50/p90/p99 latency, token throughput, RAM (before/peak/after), battery delta, thermal distribution, OOM tracking
- **Corpus generator** — 500 tasks across 5 categories (message_reply, command_parse, summarization, retrieval, calendar_reasoning); 10% adversarial rate (prompt injection, PII probing, instruction override)
- Benchmark UI in Settings — run experiments, track progress, share metrics.csv via FileProvider

### Chat Interface
- Jetpack Compose chat UI (from SmolChat-Android base)
- GGUF model download from Hugging Face Hub
- "Aigentik" folder auto-created in chat UI with Agent Activity and Benchmarks chats
- All agent events (replies sent, policy blocks, errors) appear in "Agent Activity" chat

## Installation

### GitHub (Debug APK)

1. Download the latest APK from [GitHub Releases](https://github.com/YOUR_USERNAME/Aigentik/releases/) and transfer it to your Android device.
2. If your device does not allow downloading APKs from untrusted sources, search for **how to allow downloading APKs from unknown sources** for your device.

### Build from Source

1. Clone the repository with its submodule originating from llama.cpp:

```commandline
git clone --depth=1 https://github.com/YOUR_USERNAME/Aigentik.git
cd Aigentik
git submodule update --init --recursive
```

2. Android Studio starts building the project automatically. If not, select **Build > Rebuild Project** to start a project build.

3. After a successful project build, [connect an Android device](https://developer.android.com/studio/run/device) to your system. Once connected, the name of the device must be visible in top menu-bar in Android Studio.

## CI/CD Build

Aigentik uses GitHub Actions to automatically build APKs on every push:

1. Push your code to GitHub
2. GitHub Actions will automatically build debug and release APKs
3. Download the APK from the **Actions** tab > Select the workflow run > Download artifacts

### Setting up Release Signing (Optional)

To build signed release APKs, add the following secrets to your GitHub repository:

- `KEYSTORE_BASE_64`: Base64 encoded keystore file
- `RELEASE_KEYSTORE_PASSWORD`: Keystore password
- `RELEASE_KEYSTORE_ALIAS`: Keystore alias
- `RELEASE_KEY_PASSWORD`: Key password

## Permissions

Aigentik requires the following permissions for its features:

### Core Permissions
- `INTERNET` - For model downloads and API calls
- `RECORD_AUDIO` - For voice input

### Email Permissions
- `GET_ACCOUNTS` - Access email accounts
- `USE_CREDENTIALS` - Authenticate with email providers

### Calendar Permissions
- `READ_CALENDAR` - Read calendar events
- `WRITE_CALENDAR` - Create/modify calendar events

### SMS Permissions
- `BIND_NOTIFICATION_LISTENER_SERVICE` - Intercept notifications from SMS apps for auto-reply

### Background Services
- `FOREGROUND_SERVICE` - Run background listeners
- `POST_NOTIFICATIONS` - Show notifications

## Project Structure

```
aigentik-app/
├── app/src/main/java/com/aigentik/app/
│   ├── ai/              # AgentLLMFacade (wraps SmolLM; on-demand load + idle unload)
│   ├── agent/           # ActionSchema, ActionSchemaValidator, RiskScorer, ActionPolicyEngine
│   ├── auth/            # AdminAuthManager (PBKDF2), GoogleAuthManager (OAuth2)
│   ├── benchmark/       # BenchmarkRunner, CorpusBuilder, MetricsExporter, LatencyTracer
│   ├── core/            # MessageEngine, ContactEngine, ChannelManager, AigentikService
│   ├── data/            # AppDB (Room), Chat/Message/LLMModel/Folder/TaskMetric entities
│   ├── email/           # GmailApiClient, GmailHistoryClient, EmailMonitor, EmailRouter
│   ├── llm/             # SmolLM wrapper (SmolChat base)
│   ├── sms/             # AigentikNotificationListener, NotificationReplyRouter
│   ├── system/          # BootReceiver, ConnectionWatchdog
│   └── ui/              # Jetpack Compose screens (chat, settings, model download)
├── smollm/              # Android library: JNI bindings for llama.cpp
├── smolvectordb/        # Android library: on-device vector search
├── hf-model-hub-api/    # Kotlin library: Hugging Face Hub API client
├── llama.cpp/           # Git submodule (llama.cpp inference engine)
└── .github/workflows/   # CI/CD: build + sign APK on push to main
```

## Technologies

* [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) - Pure C/C++ framework for running ML models on Android
* [noties/Markwon](https://github.com/noties/Markwon) - Markdown rendering with code syntax highlighting
* [Android Room](https://developer.android.com/training/data-storage/room) - Local database
* [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern UI toolkit
* [Koin](https://insert-koin.io/) - Dependency injection

## Usage

### First Time Setup

1. Launch Aigentik — you will be directed to **download a GGUF model** if none is present
2. After download, you are directed to **Agent Settings** to configure your name, phone number, and admin password
3. Grant **Notification Access** (Settings → Notification Access → Aigentik) — required for SMS/RCS auto-reply
4. Request **battery optimization exemption** when prompted — prevents Samsung from killing the background service
5. Optionally **Sign In with Google** in Agent Settings to enable Gmail and Google Voice auto-reply

### SMS/RCS Auto-Reply

Works out of the box for Google Messages, Samsung Messages, Verizon Messages, AT&T Messages, and other major carrier SMS apps. Requires Notification Access permission.

### Gmail Auto-Reply

1. Open the ⋮ menu in the chat screen → **Aigentik Settings**
2. Tap **Sign In with Google**
3. Grant Gmail permissions when prompted
4. Incoming emails now trigger AI replies via the Gmail History API

### Remote Admin via SMS

Send a multi-line SMS from your registered phone number:
```
Admin: YourName
Password: yourpassword
status
```
Commands: `status`, `channels`, `find [name]`, `check email`, `sync contacts`, `enable/disable sms/email/gvoice`

Destructive commands (delete, wipe, clear, mass text) require a follow-up confirmation:
```
confirm: <original command>
```

## Building APKs

### Debug APK
```bash
./gradlew assembleDebug
```

### Release APK (requires signing)
```bash
./gradlew assembleRelease
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

```
Copyright (C) 2024 Aigentik

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Acknowledgements

This project is a fork of [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) by Shubham Panchal, extended with email, calendar, and SMS integration capabilities.
# Test build
