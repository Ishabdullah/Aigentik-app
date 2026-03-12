# Research Plan: Aigentik Phase I

**Program Target:** NSF SBIR/STTR Phase I
**Project:** Privacy-Preserving On-Device Agent Framework for Reliable Mobile Autonomy
**Duration:** 6 months (Phase I)

---

## Objectives Overview

Phase I establishes three primary technical objectives with measurable success criteria. Each objective addresses a distinct unsolved problem in on-device AI agent design. Together they validate the core thesis: that safe, energy-aware, offline-capable autonomous agents can operate reliably on consumer Android hardware.

---

## Phase I Objective 1: Adaptive On-Device Model Routing Engine

### Goal
Build a routing engine that selects among multiple locally-available GGUF models at runtime based on task type, device state, and required output quality — demonstrating measurably better latency/quality tradeoffs than static single-model deployment.

### Technical Work

**Month 1–2: Task Classification and Profiling**
- Instrument the existing SmolLM inference path to collect per-request latency, RAM usage, token throughput, and output confidence scores
- Implement a lightweight task classifier (< 5ms) that categorizes incoming agent requests by type: message reply, command parse, calendar reasoning, summarization, retrieval
- Build a profiler that maps (model tier, task type) → (P50/P90 latency, RAM delta, output confidence) across Q4_K_M, Q5_K_M, Q8_0 quantizations on target device classes

**Month 3: Routing Logic and Fallback Handling**
- Implement the routing decision engine: reads device battery %, thermal state, available RAM, and task classifier output; selects model tier
- Define fallback chain: prefer Q5_K_M → fall to Q4_K_M under thermal constraint → queue task if all budgets exceeded
- Implement graceful degradation logging so every routing decision is recorded for analysis

**Month 4: Evaluation**
- Run 500-request benchmark comparing static Q4_K_M, static Q8_0, and adaptive router
- Measure: latency, RAM peak, battery draw, output confidence score, user-rated response quality (blind evaluation on 50 sample tasks)
- Primary success criterion: adaptive router achieves ≥ Q5_K_M accuracy at ≤ Q4_K_M energy cost in ≥ 70% of requests

### Success Criteria
- Routing decision overhead < 5ms (P99)
- ≥ 15% reduction in battery draw per 100 interactions vs. static Q8_0
- Output quality score ≥ 85% of Q8_0 baseline on task accuracy evaluation
- Zero crash-to-kill events from OOM during routing transitions

---

## Phase I Objective 2: Safe Autonomous Message Handling with Policy Verification

### Goal
Design and implement a policy engine that intercepts every agent-generated action, validates it against a typed schema, scores its risk, enforces confidence thresholds, and logs every decision trace — demonstrating that autonomous message handling operates within defined safety bounds without requiring per-user rule authoring.

### Technical Work

**Month 1–2: Action Schema and Type System**
- Define a complete typed action schema for all current agent capabilities: GmailReply, GmailRead, GmailDelete, SMSReply, CalendarCreate, CalendarRead, SystemCommand
- Implement schema validation that rejects malformed or out-of-schema actions before execution
- Define risk tier per action class (read → low, reply → medium, delete/send-external → high)

**Month 3: Policy Engine and Confidence Gating**
- Implement ActionPolicyEngine that accepts (action, model_confidence, risk_tier) and outputs (allow, require_approval, block)
- Build configurable threshold tables with defaults tuned from initial testing
- Implement ApprovalWorkflow for medium/high-risk actions: displays action summary to user, waits for confirmation before execution
- Implement DecisionTraceLogger: records (timestamp, action_type, confidence, policy_decision, outcome) to encrypted local store

**Month 4: Safety Evaluation**
- Construct a test corpus of 200 ambiguous or adversarial message inputs designed to trigger unsafe actions
- Measure: false positive rate (safe actions blocked), false negative rate (unsafe actions allowed), user approval burden (approvals per 100 actions)
- Primary success criterion: unsafe action rate < 1% with ≤ 10 default policy rules, with zero irreversible actions taken without user confirmation

### Success Criteria
- Schema validation rejects 100% of malformed action outputs
- Unsafe autonomous actions (irreversible, sent without approval) < 1% on test corpus
- User-visible approval requests < 5 per 100 agent interactions (avoid approval fatigue)
- Every executed action has a complete, replayable decision trace in local log

---

## Phase I Objective 3: Energy and Latency Performance on Consumer Android Hardware

### Goal
Instrument the full agent loop — from event receipt to action execution — and demonstrate quantified improvements in energy efficiency and response latency compared to a non-adaptive baseline, on a minimum of two consumer Android device classes.

### Technical Work

**Month 1: Benchmark Infrastructure**
- Build BenchmarkRunner that drives the full agent loop end-to-end with scripted inputs
- Implement MetricsStore (Room) to record: startup-to-ready time, end-to-end latency per request, token/s, RAM peak, battery % delta, thermal zone readings, OOM events
- Implement BatteryStatsCollector using Android BatteryManager API
- Implement ThermalStateCollector using PowerManager thermal status callbacks
- Add export-to-CSV/JSON for offline experiment analysis

**Month 2–3: Baseline and Adaptive Measurements**
- Establish non-adaptive baseline: single model, fixed context size, no thermal/battery gating
- Run 1000-request experiments on Device Class A (mid-range, 6GB RAM, Snapdragon 7-series) and Device Class B (flagship, 8GB RAM, Snapdragon 8-series)
- Apply energy-adaptive scheduler: implement dynamic context length reduction under thermal constraint, batch inference during charging, suspend under critical battery
- Re-run same experiment corpus under adaptive scheduler

**Month 4: Analysis and Documentation**
- Compare baseline vs. adaptive: battery drain/100 requests, latency P50/P90, thermal throttling events, OOM events, startup-to-ready time
- Identify minimum viable model size per device class
- Document tuning recommendations for production deployment

### Success Criteria
- ≥ 25% reduction in battery drain per 100 interactions under adaptive scheduler vs. non-adaptive baseline
- End-to-end reply latency P90 < 8 seconds on Device Class A (excluding model load)
- Startup-to-ready time < 45 seconds on both device classes
- Zero OOM kills during 1000-request experiment run
- Thermal throttling events reduced by ≥ 40% vs. baseline during sustained 30-minute runs

---

## Milestones and Deliverables

| Month | Milestone | Deliverable |
|---|---|---|
| 1 | Benchmark infrastructure complete | BenchmarkRunner, MetricsStore, CSV export operational |
| 2 | Task classifier + model profiler complete | Routing profiles for 3 GGUF tiers on 2 device classes |
| 2 | Action schema defined | Typed schema for all 7 action types, validator implemented |
| 3 | Routing engine integrated | End-to-end adaptive routing with fallback chain |
| 3 | Policy engine integrated | ActionPolicyEngine with approval workflow and trace logger |
| 4 | Safety evaluation complete | Test corpus results, false positive/negative rates |
| 4 | Energy evaluation complete | Comparative latency/energy results, 2 device classes |
| 5 | Memory system prototype | Compressed on-device memory with vector retrieval |
| 6 | Phase I report | Results, experiment data (CSV/JSON), Phase II roadmap |

---

## Phase II Preview

Phase II extends Phase I findings into:
1. Multi-user deployment study with real Android users over 60 days
2. Contact-aware personalization without identity leakage
3. Adversarial robustness testing of the policy engine under prompt injection attempts
4. Cross-device synchronization design using encrypted, device-owned keypairs (no cloud sync)
5. Commercialization pilot with enterprise mobile device management (MDM) partner

---

## Research Team Requirements

- Android systems engineer with NDK/JNI and llama.cpp experience
- ML engineer familiar with GGUF quantization and inference benchmarking
- Security-focused engineer for policy engine and keystore integration
- UX researcher for approval workflow user study (Phase II)
