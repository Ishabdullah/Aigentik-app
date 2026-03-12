# Technical Innovation: Aigentik

**Project Subtitle:** A privacy-preserving on-device agent framework for reliable mobile autonomy under resource constraints.

---

## Core Innovation Statement

Aigentik is not an offline assistant app. It is an architectural framework that solves the problem of **reliable, safe, energy-aware autonomous agent execution on consumer Android hardware** — without any cloud dependency, network requirement, or user identity exposure.

The fundamental innovation is the design of a **multi-layer on-device agent stack** that maintains behavioral safety guarantees, manages inference resources dynamically, and grounds decisions in verifiable policy constraints — all on hardware with 4–8 GB RAM, thermally-constrained SoCs, and intermittent power profiles.

---

## Research Challenges

### 1. Adaptive On-Device Model Routing

**Problem:** No single GGUF model provides optimal tradeoffs across latency, accuracy, memory, and battery for all task types on mobile hardware.

**Innovation:** A runtime model selection engine that:
- Classifies incoming agent tasks by type (message reply, command parse, summarization, calendar reasoning)
- Maintains a learned latency/accuracy profile per GGUF quantization tier (Q4_K_M, Q5_K_M, Q8_0)
- Routes to the appropriate model based on battery state, thermal state, and confidence threshold
- Falls back gracefully when the primary model is unavailable or exceeds resource budget

**Technical risk:** Building a lightweight meta-classifier that operates faster than the inference it is routing (< 5ms decision overhead).

---

### 2. Safe Autonomous Action Execution

**Problem:** Agents that autonomously read and reply to Gmail, handle SMS/RCS, and execute system commands can cause irreversible harm if they misinterpret intent or act under low-confidence inference.

**Innovation:** A structured action policy layer that:
- Validates every agent-generated action against a typed schema before execution
- Scores risk level per action class (read = low, reply = medium, delete/send = high)
- Enforces confidence thresholds per action class
- Requires explicit in-app approval for destructive or sensitive actions
- Logs every decision with the model's reasoning trace for post-hoc auditability

**Technical risk:** Designing a policy engine expressive enough to cover real message handling without requiring domain-specific rule authoring per user.

---

### 3. Energy-Aware Inference Management

**Problem:** LLM inference on mobile is power-intensive. A background agent running inference at peak load will drain battery in < 2 hours, cause thermal throttling, and be killed by Android's process manager.

**Innovation:** A dynamic inference scheduler that:
- Monitors thermal zone temperature, battery charge level, and CPU governor state
- Adjusts inference parameters at runtime (context length, batch size, quantization tier)
- Defers non-urgent inference tasks to charging windows
- Suspends background inference under thermal constraints while queuing requests

**Technical risk:** Proving that energy-adaptive inference maintains acceptable response quality — that reducing context/quantization does not degrade task accuracy below a usability threshold.

---

### 4. Long-Term On-Device Memory with Privacy Preservation

**Problem:** Mobile agents need personalized, context-aware behavior over weeks and months. Cloud memory is inadmissible given the privacy model. On-device raw conversation logs are too large for retrieval.

**Innovation:** A hierarchical memory system that:
- Compresses older interactions into semantic summaries using the local model
- Stores summaries as embeddings in a local vector database (smolvectordb)
- Supports per-contact personalization profiles without identity leakage
- Implements selective forgetting via decay scoring and user-controlled redaction
- Encrypts all memory stores at rest using Android Keystore

**Technical risk:** Maintaining retrieval quality after multi-stage compression and proving that the summarization loop does not introduce factual drift.

---

### 5. Reliable Startup Sequencing Under Android Lifecycle Constraints

**Problem:** The current architecture has documented races between service initialization, model loading, and incoming event dispatch. Gmail commands and SMS events arrive before the inference engine is ready, causing silent failures.

**Innovation:** A deterministic command queue with:
- Timestamped event capture before inference service is ready
- Ordered replay after initialization completes
- Health state machine that gates action execution to initialization stage
- Observable ready-state exposed to UI for user feedback

**Technical risk:** Implementing safe event capture that does not require holding long-lived wakelock or foreground service prior to model availability.

---

## Differentiation from Existing Work

| Dimension | Cloud AI Assistants | Offline Chat Apps | Aigentik |
|---|---|---|---|
| Privacy | None (cloud processing) | Partial (local model) | Full (no network required) |
| Autonomous action | Cloud-side | None | On-device with policy verification |
| Energy awareness | Cloud-side | None | Dynamic runtime adaptation |
| Safety guarantees | Vendor policy | None | Typed action schema + policy engine |
| Long-term memory | Cloud storage | None | Compressed on-device with encryption |
| Offline reliability | Fails without network | Inference only | Full agent loop offline |

---

## Technology Stack

- **Inference engine:** llama.cpp (GGUF) via JNI — `smollm` module
- **Vector retrieval:** smolvectordb (local, on-device)
- **Action channels:** Gmail API (OAuth2), Android SMS/RCS (NotificationListenerService), System calendar
- **Persistence:** Room + Android Keystore encryption
- **UI:** Jetpack Compose
- **DI:** Koin with annotation-based module generation
- **Build:** Gradle 8.x + AGP 8.x, CMake 3.22+, NDK r27b

---

## What Makes This Fundable Research (Not Just Engineering)

The research questions that remain open and require experimental answer:

1. What quantization tier produces the minimum acceptable accuracy for each action class under mobile memory constraints?
2. Can an energy-adaptive scheduler maintain < 2s reply latency while reducing peak power draw by > 30%?
3. Does multi-stage on-device memory compression introduce measurable factual drift, and at what compression ratio does quality degrade below a threshold?
4. What is the minimum safe confidence threshold for autonomous message reply, and how does it vary by contact relationship and message topic?
5. Can a policy engine reduce unsafe autonomous actions to < 1% without requiring more than 10 user-defined rules?

These are empirically answerable questions with hardware-grounded metrics on real Android devices. That is the structure of a Phase I SBIR/STTR.
