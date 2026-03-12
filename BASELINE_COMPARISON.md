# Baseline Comparison: Aigentik vs. Existing Approaches

**Purpose:** Document how Aigentik's approach differs from and improves upon existing solutions, as required by NSF SBIR evaluation criteria.

---

## Comparison Framework

NSF explicitly asks: *"How is the innovation significantly better than existing solutions, and what technical challenges must be solved to demonstrate this?"*

This document answers that across five dimensions: privacy, safety, energy efficiency, offline reliability, and long-term memory.

---

## Dimension 1: Privacy Model

### Existing Solutions

**Cloud AI assistants (Google Assistant, ChatGPT mobile, Gemini):**
- All processing occurs server-side
- Message content, contact names, and user behavioral data transmitted to vendor infrastructure
- No user control over data retention
- OAuth access tokens transmitted with each request

**Offline chat apps (llama.cpp frontends, MLC LLM, Ollama mobile):**
- Inference is local, but these are chat interfaces — not autonomous agents
- No action execution (no Gmail reply, no SMS send, no calendar write)
- No persistent memory — conversation context is session-scoped only

### Aigentik Approach
- All inference, memory, and action execution is on-device
- No network calls except Gmail/Google Calendar OAuth token refresh (required by API contract)
- No message content ever transmitted to third parties
- Contacts, conversation history, and decision traces encrypted at rest in Android Keystore

**Measurable differentiation:**
- Aigentik: zero message content leaves device
- Cloud assistants: 100% of message content processed externally
- Offline chat apps: no autonomous action capability to compare

---

## Dimension 2: Autonomous Action Safety

### Existing Solutions

**Cloud AI agents (Google Workspace extensions, Microsoft Copilot):**
- Action safety is gated by vendor policy applied server-side
- No user visibility into policy logic
- No adjustable confidence thresholds
- No local audit trail

**LangChain / AutoGPT style agents (mobile ports or localhost):**
- Action safety relies on model instruction-following
- No typed action schema enforcement
- No policy engine — unsafe actions possible if model misinterprets
- No approval workflow for destructive actions

**Existing offline Android assistants (e.g., smolchat-android, Chonkie-based tools):**
- No action execution at all — read-only chat interfaces

### Aigentik Approach
- Typed action schema: every agent output validated before execution
- Risk-tiered policy engine with adjustable thresholds
- Approval workflow for medium/high-risk actions
- Complete decision trace log per action
- Schema rejects malformed actions regardless of model instruction compliance

**Measurable differentiation target:**
- Unsafe autonomous action rate < 1% on adversarial corpus (vs. unvalidated models which produce unsafe actions on 15–40% of adversarial inputs in published red-teaming studies)

---

## Dimension 3: Energy Efficiency

### Existing Solutions

**Static GGUF deployment (standard llama.cpp Android apps):**
- Fixed model tier used for all requests regardless of device state
- No thermal adaptation — model runs at full context even under throttling
- Battery drain can reach 5–8% per 100 short inference tasks on mid-range hardware (estimated from published llama.cpp benchmarks)

**Cloud AI energy profile:**
- Client energy usage is minimal (HTTP request only)
- Server-side energy cost is externalized — not relevant to device battery life

### Aigentik Approach
- Dynamic context length adjustment based on thermal state
- Model tier selection based on battery % and thermal zone
- Inference deferred to charging windows for non-urgent tasks
- Batch processing when idle to amortize cold-start cost

**Measurable differentiation target:**
- ≥ 25% reduction in battery drain per 100 tasks vs. static Q8_0 baseline
- ≥ 40% reduction in thermal throttling events during sustained 30-minute runs

---

## Dimension 4: Offline Reliability

### Existing Solutions

**Cloud AI assistants:**
- Complete failure without network connectivity
- No graceful degradation

**Standard offline chat apps:**
- Inference works offline
- No action execution (no reliability concern — no actions to fail)

**Existing Android agents with Gmail/messaging integration:**
- None found that operate fully offline — all require cloud inference or cloud API for action execution

### Aigentik Approach
- Complete agent loop (inference + action execution) requires zero network connectivity
- Exception: Gmail API requires OAuth token refresh periodically — cached tokens extend offline window
- SMS/RCS path is fully offline (NotificationListenerService + telephony)
- Calendar read/write is fully offline (system calendar)

**Measurable differentiation target:**
- ≥ 95% of agent tasks completable without network connectivity
- Gmail path: document maximum offline window with cached token
- SMS/calendar path: 100% offline

---

## Dimension 5: Long-Term Memory

### Existing Solutions

**Cloud AI assistants:**
- Memory stored server-side with vendor retention policies
- User has no control over retention, access, or deletion

**Standard offline chat apps:**
- No persistent memory — each session is independent
- Or: raw conversation log (unbounded, no retrieval)

**On-device vector search tools (separate category):**
- Exist as standalone libraries (smolvectordb, FAISS Android ports)
- Not integrated into agent loop with compression/decay

### Aigentik Approach
- Hierarchical memory: recent context in RAM → compressed summaries in vector store → long-term embeddings
- Summarization via local model (no cloud required)
- Per-contact personalization profiles stored locally
- Encrypted at rest, selective redaction, decay scoring for aging memories

**Measurable differentiation target:**
- Retrieval quality (MRR@5) > 0.70 after 3 stages of compression vs. raw log baseline
- Memory store size < 50 MB after 90 days of daily use (compression effectiveness)

---

## Quantitative Comparison Summary

| Metric | Cloud AI Assistant | Static Offline App | **Aigentik Target** |
|---|---|---|---|
| Message privacy | 0% (all server-side) | 100% local | **100% local** |
| Autonomous action | Yes, cloud-gated | No | **Yes, policy-gated** |
| Unsafe action rate (adversarial) | Unknown/vendor-opaque | N/A | **< 1%** |
| Battery drain / 100 tasks | ~0% (HTTP only) | ~5–8% (static Q8_0) | **< 4% (adaptive)** |
| Offline task completion rate | 0% | 100% (chat only) | **≥ 95% (full agent)** |
| Long-term memory | Server-side | None | **On-device, compressed** |
| Startup-to-ready time | ~500ms (HTTP) | 30–120s (model load) | **< 45s** |
| Action audit trail | None (user-visible) | N/A | **Full local trace** |

---

## Gaps in Existing Literature

We are not aware of published peer-reviewed work that addresses all of the following simultaneously:
- Autonomous agent action execution on mobile Android (not desktop/server)
- Full offline operation without cloud inference
- Hardware-adaptive inference under thermal and battery constraints
- Typed action schema enforcement for agent output validation
- On-device compressed memory with retrieval quality measurement

The closest adjacent work is:
- **MLC LLM** (inference optimization, no agent layer)
- **llama.cpp Android** (inference library, no agent/action/safety layer)
- **LangChain on Android** (agent framework, cloud inference assumed, no offline path)
- **Private LLM** (commercial iOS/Android app, no published safety/energy benchmarks)

Aigentik's contribution is the integrated design that addresses all five dimensions on consumer hardware, with a published benchmark methodology and reproducible experimental results.
