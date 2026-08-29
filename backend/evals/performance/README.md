# Phase 4 Step 4.4 layered performance workflow

This directory contains a fail-closed, local-only performance workflow for
Apache JMeter 5.6.3. It validates configuration by default. It never starts the
application, Docker, MySQL, Milvus, or any external provider.

## Scope and evidence boundary

The single JMeter plan contains three selectable branches:

| Layer | Operation | What it can establish |
| --- | --- | --- |
| L1 | `POST /internal/performance/retrieval` with one caller-supplied, fixed query payload; `degraded=false` and `status=success` are both required | Retrieval-path latency, throughput and errors for a non-empty, non-degraded local retrieval result; the response exposes safe per-request stage timing, but the current runner does not turn it into percentile evidence |
| L2 | `POST /api/documents/{documentId}/ingest` (HTTP 202), then poll `GET /api/ingestion/jobs/{jobId}` to `SUCCEEDED`, `FAILED`, or `CANCELLED` | End-to-end submission-to-terminal latency in explicitly disposable state |
| L3 | `POST /api/agent/chat`; the evidence decision must be `ANSWER` | Retrieval, rerank, and answer assembly latency for an answerable fixed question under the local application configuration |
| L4 | Deferred | Multi-host, internet-facing, production, or destructive capacity testing is not implemented and is always rejected |

Evidence levels are deliberately separate:

- `FIXTURE_STATIC`: JSON/XML/schema/unit-test validation only. It sends no HTTP
  and proves no runtime performance.
- `RUNTIME`: an explicitly authorized loopback run with Actuator health plus
  CPU and heap sampling. Missing health or metrics makes runtime evidence
  `INVALID`; values are never invented.
- `PRODUCTION`: reserved by the versioned schema. This workflow cannot produce
  production evidence because execution is restricted to loopback.

A passing validate-only command is therefore `NOT_RUN`, not a baseline. A
completed local run is runtime evidence only, not production readiness.

## Privacy and isolation contract

The JMX, profile, command line, JTL, and report do not contain query text,
request or response bodies, token values, sensitive headers, document/job/chat
business IDs, or source content. Runtime-only values are read from these
process environment variables:

- `RAG_PERFORMANCE_TOKEN`: L1 `X-Performance-Token` value.
- `RAG_PERFORMANCE_L1_BODY`: fixed L1 JSON payload with `courseId`, `query`, and
  `topK`.
- `RAG_PERFORMANCE_DOCUMENT_ID_POOL`: JSON array containing a run-scoped pool
  of unique disposable L2 document IDs. Each ID is consumed at most once across
  warm-up and steady windows.
- `RAG_PERFORMANCE_L3_BODY`: L3 JSON payload with `courseId` and `question`;
  `sessionId` must be absent or `null` so every request creates a fresh session.

Supply those values from an authorized parent process or secret manager. Do not
put them in a profile, command argument, checked-in file, transcript, or report.
The runner validates their shape in memory and never prints or persists them.

JTL output contains timestamps, elapsed time, sampler labels, response codes,
success flags, byte counts, and thread counts only. Request/response data,
sampler data, URL, assertion messages, and request/response headers are disabled.
Per-invocation JMeter engine logs are isolated and removed before the workflow
returns, including failure paths; raw engine logs are not report artifacts.

Every real run uses an exclusive-create directory under `runs/` by default.
An existing run directory or report is never overwritten. The runner records
Git HEAD and a dirty fingerprint that includes the complete Git index (so a
staged-only blob cannot disappear), tracked changes, and untracked content
(excluding ignored output). It then copies the validated profile, JMX, and
pinned report schema into the run directory and executes only those snapshots.
The process environment is copied once at workflow entry, so later mutations by
the parent process cannot change the fingerprinted payload or ID pool. The
snapshot hashes and the JMeter launcher-file hash are checked before and after
every invocation and again before the report is written; original sources and
Git state are also checked at the end. Any drift changes the run to
`FAILED / INVALID / SOURCE_DRIFT`, even though snapshots and partial JTL remain
available for diagnosis. `jmeterExecutableSha256` fingerprints only the
resolved launcher file; it is not a fingerprint of the complete JMeter
installation or its dependency jars.

## Profile gates

`performance-profile.example.json` is intentionally safe and non-executable:

- concurrency `1, 2, 4, 8`;
- 60-second warm-up, excluded from reported results;
- 180-second steady state per enabled layer and concurrency;
- stop when error rate is greater than `0.01`;
- stop when P99 is greater than two times the lowest-concurrency P99 for two
  consecutive analysis windows;
- stop when CPU remains greater than `0.85` for 120 continuously sampled
  seconds; a sample at or below the threshold resets that duration;
- stop immediately when heap usage is greater than `0.80`, or when any HTTP
  429 is observed;
- `execution.enabled=false` and
  `execution.disposableStateConfirmed=false`;
- external mode `disabled`, budget `0`, and pricing version `null`.

The `workload` section is also fail-closed. An executable profile must declare
a runtime build SHA-256, a fixture SHA-256, the stable request-model ID, and the
applicable corpus/file scale. The fixture fingerprint binds those low-cardinality
values to the actual environment-only L1/L2/L3 payloads. Generate it locally,
without HTTP or persistence, after setting the runtime environment variables:

```powershell
python -X utf8 backend/evals/performance/run_performance.py `
  --fingerprint-workload --profile <authorized-profile-path>
```

Copy only the resulting SHA-256 into
`workload.fixtureFingerprintSha256`. A changed query, question, document-ID
pool, enabled layer, request model, or declared scale then fails the execution
gate. The declared corpus/file counts remain operator-owned scale metadata;
this workflow does not query the database to independently count the corpus.

Changing only a CLI flag is insufficient. Execution requires all of these:

1. explicit `--execute` (or PowerShell `-Execute`);
2. `execution.enabled=true` in the selected profile;
3. an HTTP(S) base URL whose host is exactly `localhost` or a loopback IP;
4. a separate loopback Actuator origin (`actuator.baseUrl`, recommended
   `http://127.0.0.1:8081`) for health/resource/external-call evidence;
5. `execution.disposableStateConfirmed=true`;
6. `layers.L4=false`;
7. a valid external policy/budget pair;
8. the required process environment values;
9. the environment-only workload exactly matches the fixture fingerprint;
10. the pinned report-schema contract, Apache JMeter exactly `5.6.3`, and a
    readable launcher file whose SHA-256 stays stable for the run;
11. Actuator health `UP`, readable CPU/heap metrics, and a live runtime-build
    fingerprint matching the profile.

For the runtime identity gate, hash the exact built application artifact and
start that application with
`RAG_PERFORMANCE_RUNTIME_BUILD_FINGERPRINT_SHA256=<artifact SHA-256>` while the
`performance` Spring profile is active. `/actuator/info` publishes only that
attestation, and the runner compares it before and after the run. This binds the
report to the identity presented by the live process; it is not a hash of
process memory and must not be described as independent binary attestation.

The runner first performs an unreported warm-up for each enabled layer, then
runs each configured steady-state concurrency. Every warm-up and steady window
must have both an actual JMeter invocation duration and a primary-sample span of
at least 90% of the configured duration, and every configured JMeter thread
must join near the start, remain evidenced through the stable-window tail, and
have no unsupported inactive gap longer than 10% of the configured window.
A long request covers its own elapsed interval. Throughput uses invocation
wall-clock time. Sparse first/last samples, early thread exit, or test-plan exit
therefore fail runtime evidence instead of inflating throughput. A validated
JSR223 postprocessor stops the JMeter test immediately
on HTTP 429; the runner recognizes that stop before applying full-window checks.
Error-rate conditions also apply during warm-up. The CPU consecutive-duration
state and monotonic resource timeline are shared across all layer/concurrency
invocations. The runner does not start or repair any dependency. The
application and disposable data must already exist and be owned by the
operator.

L2 does not accept one reusable ID. The runner partitions the unique ID pool
across all L2 invocations and JMeter atomically consumes one ID per ingest. A
duplicate, undersized, or early-exhausted pool fails runtime evidence instead
of measuring idempotent job reuse as ingestion throughput.

L3 must target an operator-owned disposable course/state fixture. A fixed,
non-null session ID is rejected because concurrent requests would share and
grow chat history instead of exercising a fixed workload. JMeter evaluates
responses in memory and fails any L1 sample unless `degraded` is exactly
`false` and `status` is exactly `success`, or any L3 sample whose
`data.metadata.evidenceDecision.decision` is not exactly `ANSWER`. Response
bodies and assertion messages remain excluded from JTL and reports.

The example profile also keeps all runtime execution `NOT_READY`:
`external.mode` is `disabled`, but that declaration alone cannot prove the
running application will not call a query-embedding, ingest-embedding, rerank,
or chat provider. With a zero budget, execution requires a local Actuator
counter path in `external.zeroExternalCallMetricPath`; the runner verifies that
the counter is readable and unchanged around every enabled-layer warm-up and
steady window. Because the current example has this field `null`, all layers
remain validate-only. `provider-budgeted` execution is rejected while provider
usage and pricing evidence are unavailable; a real-provider run must remain
`NOT_RUN`.

## Commands

From the repository root, default validation is read-only and sends no HTTP:

```powershell
python -X utf8 backend/evals/performance/run_performance.py
```

The wrapper has the same default:

```powershell
backend/evals/performance/run-performance.ps1
```

Run unit and XML contract tests without a workload:

```powershell
python -X utf8 -m unittest discover -s backend/evals/performance/tests -v
```

Optionally ask JMeter 5.6.3 to load the plan without network samplers. The
JMX itself defaults `PERF_VALIDATE_ONLY` to `true`; the explicit property below
documents that intent and makes every L1-L3 branch unreachable. Only the gated
Python runner passes `false` after all execution preconditions succeed:

```powershell
jmeter -n -t backend/evals/performance/rag-layered-performance.jmx `
  -JPERF_VALIDATE_ONLY=true -JPERF_THREADS=1 -JPERF_DURATION_SECONDS=1 `
  -JPERF_JTL="$env:TEMP\rag-performance-static-load.jtl"
```

An authorized real run is intentionally verbose at the gate, but secrets and
payloads remain environment-only:

```powershell
python -X utf8 backend/evals/performance/run_performance.py `
  --execute `
  --profile <authorized-profile-path> `
  --jmeter <apache-jmeter-5.6.3 executable> `
  --output-root backend/evals/performance/runs
```

Do not run that command against shared, persistent, non-loopback, or production
state.

## Report semantics

`performance-report-v1.schema.json` is hash-pinned by the runner and versions
the artifact. `runStatus` is one of `NOT_RUN`, `COMPLETED`, `STOPPED`, or
`FAILED`. `runtimeEvidenceStatus` is separately `NOT_RUN`, `VALID`, or
`INVALID`; it is never called `PASS`. `thresholdStatus` distinguishes
`WITHIN_PROFILE`, `STOPPED_BY_THRESHOLD`, `NOT_EVALUATED`, and `INVALID`.
Thus a valid partial run stopped by a safety threshold cannot be mistaken for a
performance Gate pass.

The Python validator closes status combinations that JSON field types alone
cannot express: `STOPPED` requires a declared threshold reason; `COMPLETED`
requires non-empty result and sampler collections; every completed result must
contain samples from every configured thread; and any `VALID` runtime report
requires non-empty measured resource samples and peaks. `FAILED` requires a
sanitized failure reason. The pinned schema SHA-256 is
`70a32c459b03a76f619a03ac29c8c8d25aaf9984a21fda4e1bc86f9d37ca6169`.

`results` and `samplerMetrics` contain invocation duration, observed sample
span, participating thread count, sample count, nearest-rank P50/P95/P99,
throughput, error rate, 429 count, and 5xx count. P99 consecutive-window logic
uses only complete, contiguous buckets; a missing bucket breaks the sequence
and the incomplete final bucket is ignored. `samplerMetrics` are JMeter sampler
aggregates, not Java Trace percentiles. `internalStageMetrics` therefore remains
explicitly `NOT_COLLECTED` until a safe, versioned scraper exists. Resource
samples are ratios from Actuator only.

Provider usage remains `UNKNOWN` and numeric fields remain `null` unless a
future evidence source measures them directly. Cost is never estimated from
characters, response bytes, or elapsed time. The current runner does not claim
provider usage and therefore always preserves `UNKNOWN/null`.
