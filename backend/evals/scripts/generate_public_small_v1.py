#!/usr/bin/env python3
"""Generate the deterministic, fully synthetic public-small-v1 RAG dataset."""

from __future__ import annotations

import hashlib
import json
import math
import random
import re
from pathlib import Path
from typing import Any, Iterable


DATASET_ID = "public-small-v1"
SCHEMA_VERSION = 1
DATASET_VERSION = "1.0.0"
SEED = 20260809
EMBEDDING_DIMENSION = 12
CANDIDATE_K = 8
TOP_K = 5
VECTOR_WEIGHT = 0.7
LEXICAL_WEIGHT = 0.3

SCRIPT_DIR = Path(__file__).resolve().parent
OUTPUT_DIR = SCRIPT_DIR.parent / "datasets" / DATASET_ID


def chunk(
    chunk_id: int,
    document_id: int,
    source_id: str,
    chunk_index: int,
    title: str,
    content: str,
    coordinates: dict[int, float],
) -> dict[str, Any]:
    return {
        "chunkId": chunk_id,
        "documentId": document_id,
        "sourceId": source_id,
        "chunkIndex": chunk_index,
        "title": title,
        "content": content,
        "truncated": False,
        "license": "CC0-1.0",
        "_coordinates": coordinates,
    }


CHUNKS = [
    chunk(
        910000000000000001,
        710000000000000001,
        "source-atlas-cache-guide",
        0,
        "Atlas refresh window",
        "Atlas cache entries use a soft TTL of ninety seconds. A reader may serve the current value during that interval, while a background refresh obtains a newer value before the hard expiry.",
        {0: 1.0, 7: 0.9, 5: 0.1},
    ),
    chunk(
        910000000000000002,
        710000000000000001,
        "source-atlas-cache-guide",
        1,
        "Atlas stale grace rule",
        "Atlas stale grace is separate from retry delay. After soft TTL expiry, one elected reader refreshes the entry and other readers may use the stale value for thirty seconds; after hard expiry no stale value is served.",
        {0: 1.0, 7: 0.7, 5: 0.25},
    ),
    chunk(
        910000000000000003,
        710000000000000001,
        "source-atlas-cache-guide",
        2,
        "Atlas cache key namespace",
        "An Atlas cache key contains tenant, resource type, and stable resource identifier in that order. A deployment color is excluded so blue and green workers resolve the same logical entry.",
        {0: 0.9, 10: 0.9, 2: 0.1},
    ),
    chunk(
        910000000000000004,
        710000000000000001,
        "source-atlas-cache-guide",
        3,
        "Atlas eviction watermark",
        "Atlas starts approximate least recently used eviction at eighty percent capacity and stops at seventy percent. Eviction changes memory pressure only; it does not alter soft TTL or hard expiry policy.",
        {0: 1.0, 11: 0.6, 7: 0.2},
    ),
    chunk(
        910000000000000005,
        710000000000000002,
        "source-nimbus-queue-manual",
        0,
        "Nimbus acknowledgement lease",
        "Nimbus gives a consumer a forty second visibility lease. The consumer must acknowledge before the lease ends, or extend the lease while work continues; an expired lease makes the message visible again.",
        {1: 1.0, 5: 0.45},
    ),
    chunk(
        910000000000000006,
        710000000000000002,
        "source-nimbus-queue-manual",
        1,
        "Nimbus retry schedule",
        "Nimbus retry delay follows fixed steps of five, twenty, and sixty seconds after consecutive failed deliveries. Visibility lease duration controls in flight ownership and is not one of the retry delay steps.",
        {1: 0.9, 5: 1.0},
    ),
    chunk(
        910000000000000007,
        710000000000000002,
        "source-nimbus-queue-manual",
        2,
        "Nimbus quarantine threshold",
        "Nimbus moves a message to the dead letter quarantine after four failed delivery attempts. The quarantine record keeps the original message identifier, final error class, and attempt count for inspection.",
        {1: 1.0, 5: 0.65, 11: 0.35},
    ),
    chunk(
        910000000000000008,
        710000000000000002,
        "source-nimbus-queue-manual",
        3,
        "Nimbus duplicate suppression",
        "A Nimbus producer supplies a stable deduplication token for each logical command. The broker retains the token for ten minutes and accepts a later command as new only after that retention window.",
        {1: 0.95, 10: 0.85},
    ),
    chunk(
        910000000000000009,
        710000000000000003,
        "source-orchid-deployment-notes",
        0,
        "Orchid canary stages",
        "Orchid canary deployment sends five percent of traffic for ten minutes, then twenty five percent for fifteen minutes, before full rollout. Each stage advances only when its health gate passes.",
        {2: 1.0, 8: 1.0},
    ),
    chunk(
        910000000000000010,
        710000000000000003,
        "source-orchid-deployment-notes",
        1,
        "Orchid rollback gate",
        "Orchid triggers automatic rollback when the canary error ratio exceeds two percent for three consecutive one minute windows. A single brief spike records a warning but does not trigger rollback.",
        {2: 1.0, 8: 0.75, 4: 0.35},
    ),
    chunk(
        910000000000000011,
        710000000000000003,
        "source-orchid-deployment-notes",
        2,
        "Orchid configuration freeze",
        "Orchid freezes configuration mutation from the start of canary traffic until rollout completion or rollback completion. Read only inspection remains available throughout the freeze.",
        {2: 0.9, 10: 0.55, 8: 0.2},
    ),
    chunk(
        910000000000000012,
        710000000000000003,
        "source-orchid-deployment-notes",
        3,
        "Orchid worker drain",
        "Before replacing an Orchid worker, the controller stops new assignments and allows active work to drain for up to forty five seconds. Remaining work is returned to the queue before process termination.",
        {2: 0.85, 1: 0.4, 5: 0.2},
    ),
    chunk(
        910000000000000013,
        710000000000000004,
        "source-quartz-storage-spec",
        0,
        "Quartz segment verification",
        "Quartz verifies every sealed segment with a SHA-256 digest stored beside the segment descriptor. Recovery rejects a segment when the calculated digest differs from the descriptor digest.",
        {3: 1.0, 6: 1.0},
    ),
    chunk(
        910000000000000014,
        710000000000000004,
        "source-quartz-storage-spec",
        1,
        "Quartz replica verification",
        "A Quartz replica independently calculates SHA-256 for each sealed segment and compares it with the descriptor value before activation. Any mismatch leaves that segment unavailable for reads.",
        {3: 1.0, 6: 0.97},
    ),
    chunk(
        910000000000000015,
        710000000000000004,
        "source-quartz-storage-spec",
        2,
        "Quartz immutable read generation",
        "A Quartz read transaction binds to one immutable generation selected by the catalog pointer. Later compaction may publish another generation, but the active reader continues with its original generation until close.",
        {3: 1.0, 10: 0.65},
    ),
    chunk(
        910000000000000016,
        710000000000000004,
        "source-quartz-storage-spec",
        3,
        "Quartz compaction glossary warning",
        "Quartz compaction logs may contain the labels snapshot reader version catalog isolation when describing diagnostics. Those labels are search hints only; this section defines compaction scheduling at a sixty percent fragmentation threshold.",
        {3: 1.0, 11: 0.65},
    ),
    chunk(
        910000000000000017,
        710000000000000005,
        "source-zephyr-observability-runbook",
        0,
        "Zephyr trace sampling",
        "Zephyr keeps every trace that contains an error and samples one percent of otherwise successful traces. The decision is made at the root span and propagated to child spans.",
        {4: 1.0, 9: 1.0},
    ),
    chunk(
        910000000000000018,
        710000000000000005,
        "source-zephyr-observability-runbook",
        1,
        "Zephyr latency alert",
        "Zephyr opens a latency alert when the five minute p95 exceeds eight hundred milliseconds in three consecutive windows. One isolated slow window remains visible on the dashboard without paging.",
        {4: 1.0, 8: 0.3},
    ),
    chunk(
        910000000000000019,
        710000000000000005,
        "source-zephyr-observability-runbook",
        2,
        "Zephyr log redaction",
        "Zephyr replaces customer identifiers with a deterministic one way alias before log storage. Operators can correlate events that share an alias but cannot recover the original identifier from the log entry.",
        {4: 1.0, 10: 0.55},
    ),
    chunk(
        910000000000000020,
        710000000000000005,
        "source-zephyr-observability-runbook",
        3,
        "Zephyr span correlation",
        "Zephyr copies the root trace identifier into each structured event and attaches the active span identifier when available. This permits correlation between trace spans and service logs.",
        {4: 0.95, 9: 0.85, 10: 0.3},
    ),
]


def evidence_group(group_id: str, chunk_ids: Iterable[int]) -> dict[str, Any]:
    return {"groupId": group_id, "chunkIds": sorted(chunk_ids)}


def case(
    case_id: str,
    query: str,
    category: str,
    answerable: bool,
    relevant: Iterable[int] = (),
    acceptable: Iterable[int] = (),
    groups: Iterable[dict[str, Any]] = (),
    sources: Iterable[str] = (),
    confusing: Iterable[int] = (),
) -> dict[str, Any]:
    return {
        "caseId": case_id,
        "query": query,
        "category": category,
        "answerable": answerable,
        "relevantChunkIds": sorted(relevant),
        "acceptableChunkIds": sorted(acceptable),
        "requiredEvidenceGroups": sorted(groups, key=lambda item: item["groupId"]),
        "requiredSourceIds": sorted(sources),
        "confusingChunkIds": sorted(confusing),
    }


CASES = [
    case(
        "public-small-v1-case-001",
        "What is the Atlas soft TTL and what happens during that window?",
        "single-evidence",
        True,
        [910000000000000001],
        [910000000000000001],
        [evidence_group("group-atlas-soft-ttl", [910000000000000001])],
        ["source-atlas-cache-guide"],
        [910000000000000002, 910000000000000004],
    ),
    case(
        "public-small-v1-case-002",
        "How do Nimbus visibility lease expiry and retry delay work together after failed delivery?",
        "multi-evidence",
        True,
        [910000000000000005, 910000000000000006],
        [910000000000000005, 910000000000000006],
        [
            evidence_group("group-nimbus-retry-delay", [910000000000000006]),
            evidence_group("group-nimbus-visibility-lease", [910000000000000005]),
        ],
        ["source-nimbus-queue-manual"],
        [910000000000000007],
    ),
    case(
        "public-small-v1-case-003",
        "Which Orchid canary error ratio pattern triggers automatic rollback?",
        "concept-disambiguation",
        True,
        [910000000000000010],
        [910000000000000010],
        [evidence_group("group-orchid-rollback-gate", [910000000000000010])],
        ["source-orchid-deployment-notes"],
        [910000000000000009, 910000000000000018],
    ),
    case(
        "public-small-v1-case-004",
        "How does Quartz verify a sealed segment before it is used?",
        "alternative-evidence",
        True,
        [910000000000000013, 910000000000000014],
        [910000000000000013, 910000000000000014],
        [evidence_group("group-quartz-segment-digest", [910000000000000013, 910000000000000014])],
        ["source-quartz-storage-spec"],
        [910000000000000016],
    ),
    case(
        "public-small-v1-case-005",
        "What traces does Zephyr keep under its trace sampling rule?",
        "single-evidence",
        True,
        [910000000000000017],
        [910000000000000017],
        [evidence_group("group-zephyr-trace-sampling", [910000000000000017])],
        ["source-zephyr-observability-runbook"],
        [910000000000000020],
    ),
    case(
        "public-small-v1-case-006",
        "How can a stable resource be correlated across Atlas cache entries and Zephyr logs without storing its original identifier in logs?",
        "multi-source-evidence",
        True,
        [910000000000000003, 910000000000000019],
        [910000000000000003, 910000000000000019],
        [
            evidence_group("group-atlas-stable-resource-key", [910000000000000003]),
            evidence_group("group-zephyr-one-way-alias", [910000000000000019]),
        ],
        ["source-atlas-cache-guide", "source-zephyr-observability-runbook"],
        [910000000000000008, 910000000000000020],
    ),
    case(
        "public-small-v1-case-007",
        "Which encryption cipher protects Nimbus message bodies at rest?",
        "unanswerable-abstention",
        False,
        confusing=[910000000000000008, 910000000000000013],
    ),
    case(
        "public-small-v1-case-008",
        "In which geographic region is the Orchid control plane hosted?",
        "unanswerable-abstention",
        False,
        confusing=[910000000000000009, 910000000000000011],
    ),
    case(
        "public-small-v1-case-009",
        "After how many failed delivery attempts does Nimbus move a message to dead letter quarantine?",
        "rerank-promotion",
        True,
        [910000000000000007],
        [910000000000000007],
        [evidence_group("group-nimbus-quarantine-threshold", [910000000000000007])],
        ["source-nimbus-queue-manual"],
        [910000000000000006],
    ),
    case(
        "public-small-v1-case-010",
        "How does Quartz snapshot reader version catalog isolation behave during compaction?",
        "rerank-regression",
        True,
        [910000000000000015],
        [910000000000000015],
        [evidence_group("group-quartz-immutable-generation", [910000000000000015])],
        ["source-quartz-storage-spec"],
        [910000000000000016],
    ),
    case(
        "public-small-v1-case-011",
        "Does Atlas stale grace use the Nimbus retry delay steps?",
        "similar-concept-boundary",
        True,
        [910000000000000002],
        [910000000000000002],
        [evidence_group("group-atlas-stale-grace-boundary", [910000000000000002])],
        ["source-atlas-cache-guide"],
        [910000000000000006],
    ),
    case(
        "public-small-v1-case-012",
        "What are the first Orchid canary traffic stage and the worker drain limit used during replacement?",
        "multi-evidence",
        True,
        [910000000000000009, 910000000000000012],
        [910000000000000009, 910000000000000012],
        [
            evidence_group("group-orchid-first-canary-stage", [910000000000000009]),
            evidence_group("group-orchid-worker-drain", [910000000000000012]),
        ],
        ["source-orchid-deployment-notes"],
        [910000000000000010, 910000000000000005],
    ),
]


QUERY_VECTOR_PLANS: dict[str, list[tuple[int, float]]] = {
    "public-small-v1-case-001": [(910000000000000001, 1.0), (910000000000000002, 0.15)],
    "public-small-v1-case-002": [(910000000000000005, 0.8), (910000000000000006, 0.8)],
    "public-small-v1-case-003": [(910000000000000009, 0.9), (910000000000000010, 0.55)],
    "public-small-v1-case-004": [(910000000000000013, 0.8), (910000000000000014, 0.8)],
    "public-small-v1-case-005": [(910000000000000017, 1.0), (910000000000000020, 0.2)],
    "public-small-v1-case-006": [(910000000000000003, 0.75), (910000000000000019, 0.75)],
    "public-small-v1-case-007": [(910000000000000008, 0.35), (910000000000000013, 0.35)],
    "public-small-v1-case-008": [(910000000000000009, 0.4), (910000000000000011, 0.4)],
    "public-small-v1-case-009": [(910000000000000006, 0.92), (910000000000000007, 0.5)],
    "public-small-v1-case-010": [(910000000000000015, 1.0), (910000000000000016, 0.5)],
    "public-small-v1-case-011": [(910000000000000002, 0.75), (910000000000000006, 0.5)],
    "public-small-v1-case-012": [(910000000000000009, 0.75), (910000000000000012, 0.75)],
}


SOURCES = [
    {
        "sourceId": "source-atlas-cache-guide",
        "documentId": 710000000000000001,
        "title": "Atlas Cache Guide",
        "origin": "original-synthetic",
        "license": "CC0-1.0",
    },
    {
        "sourceId": "source-nimbus-queue-manual",
        "documentId": 710000000000000002,
        "title": "Nimbus Queue Manual",
        "origin": "original-synthetic",
        "license": "CC0-1.0",
    },
    {
        "sourceId": "source-orchid-deployment-notes",
        "documentId": 710000000000000003,
        "title": "Orchid Deployment Notes",
        "origin": "original-synthetic",
        "license": "CC0-1.0",
    },
    {
        "sourceId": "source-quartz-storage-spec",
        "documentId": 710000000000000004,
        "title": "Quartz Storage Specification",
        "origin": "original-synthetic",
        "license": "CC0-1.0",
    },
    {
        "sourceId": "source-zephyr-observability-runbook",
        "documentId": 710000000000000005,
        "title": "Zephyr Observability Runbook",
        "origin": "original-synthetic",
        "license": "CC0-1.0",
    },
]


def stable_random(key: str) -> random.Random:
    digest = hashlib.sha256(f"{SEED}:{key}".encode("utf-8")).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def normalize(values: Iterable[float]) -> list[float]:
    vector = list(values)
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0.0:
        raise ValueError("zero vector cannot be normalized")
    return [round(value / norm, 12) for value in vector]


def vector_from_coordinates(key: str, coordinates: dict[int, float]) -> list[float]:
    rng = stable_random(f"chunk:{key}")
    values = [rng.uniform(-0.025, 0.025) for _ in range(EMBEDDING_DIMENSION)]
    for index, value in coordinates.items():
        values[index] += value
    return normalize(values)


def blend_vector(
    case_id: str,
    plan: list[tuple[int, float]],
    chunk_vectors: dict[int, list[float]],
) -> list[float]:
    rng = stable_random(f"query:{case_id}")
    values = [rng.uniform(-0.01, 0.01) for _ in range(EMBEDDING_DIMENSION)]
    for chunk_id, weight in plan:
        for index, value in enumerate(chunk_vectors[chunk_id]):
            values[index] += weight * value
    return normalize(values)


def cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def tokenize(text: str) -> set[str]:
    return {piece for piece in re.findall(r"[a-z0-9]+", text.lower()) if len(piece) > 2}


def lexical_score(query: str, content: str) -> float:
    query_terms = tokenize(query)
    if not query_terms:
        return 0.0
    return len(query_terms & tokenize(content)) / len(query_terms)


def best_rank(ranking: list[int], relevant: list[int]) -> int | None:
    positions = [ranking.index(chunk_id) + 1 for chunk_id in relevant if chunk_id in ranking]
    return min(positions) if positions else None


def add_retrieval_expectations(
    cases: list[dict[str, Any]],
    chunks: list[dict[str, Any]],
    chunk_vectors: dict[int, list[float]],
    query_vectors: dict[str, list[float]],
) -> None:
    content_by_id = {item["chunkId"]: item["content"] for item in chunks}
    all_chunk_ids = sorted(content_by_id)
    for item in cases:
        query_vector = query_vectors[item["caseId"]]
        vector_scores = {
            chunk_id: cosine(query_vector, chunk_vectors[chunk_id])
            for chunk_id in all_chunk_ids
        }
        candidates = sorted(all_chunk_ids, key=lambda value: (-vector_scores[value], value))[:CANDIDATE_K]
        combined_scores = {
            chunk_id: VECTOR_WEIGHT * vector_scores[chunk_id]
            + LEXICAL_WEIGHT * lexical_score(item["query"], content_by_id[chunk_id])
            for chunk_id in candidates
        }
        reranked = sorted(candidates, key=lambda value: (-combined_scores[value], value))
        before = best_rank(candidates, item["relevantChunkIds"])
        after = best_rank(reranked, item["relevantChunkIds"])
        if not item["answerable"]:
            effect = "not-applicable"
        elif before is None and after is None:
            effect = "neutral"
        elif before is None:
            effect = "improves"
        elif after is None:
            effect = "degrades"
        elif after < before:
            effect = "improves"
        elif after > before:
            effect = "degrades"
        else:
            effect = "neutral"
        item["retrievalExpectations"] = {
            "exactCosineCandidateChunkIds": candidates,
            "rerankedTopChunkIds": reranked[:TOP_K],
            "relevantBestRankBefore": before,
            "relevantBestRankAfter": after,
            "rerankEffect": effect,
        }


def validate_dataset(
    chunks: list[dict[str, Any]],
    cases: list[dict[str, Any]],
    chunk_vectors: dict[int, list[float]],
    query_vectors: dict[str, list[float]],
) -> None:
    chunk_ids = [item["chunkId"] for item in chunks]
    case_ids = [item["caseId"] for item in cases]
    source_ids = {item["sourceId"] for item in SOURCES}
    source_by_chunk = {item["chunkId"]: item["sourceId"] for item in chunks}

    if chunk_ids != sorted(chunk_ids) or len(chunk_ids) != len(set(chunk_ids)):
        raise ValueError("chunkId values must be unique and sorted")
    if case_ids != sorted(case_ids) or len(case_ids) != len(set(case_ids)):
        raise ValueError("caseId values must be unique and sorted")
    if not all(2**31 < value < 2**63 for value in chunk_ids):
        raise ValueError("chunkId values must be stable long integers")
    document_ids = {item["documentId"] for item in chunks}
    if not all(2**31 < value < 2**63 for value in document_ids):
        raise ValueError("documentId values must be stable long integers")
    if set(source_by_chunk.values()) != source_ids:
        raise ValueError("corpus sources must match the source registry")
    if set(chunk_vectors) != set(chunk_ids) or set(query_vectors) != set(case_ids):
        raise ValueError("embedding coverage is incomplete")
    for vector in [*chunk_vectors.values(), *query_vectors.values()]:
        if len(vector) != EMBEDDING_DIMENSION:
            raise ValueError("embedding dimension mismatch")
        if abs(math.sqrt(sum(value * value for value in vector)) - 1.0) > 1e-9:
            raise ValueError("embedding is not a unit vector")

    unanswerable_count = 0
    has_alternative_group = False
    for item in cases:
        relevant = item["relevantChunkIds"]
        acceptable = item["acceptableChunkIds"]
        groups = item["requiredEvidenceGroups"]
        required_sources = item["requiredSourceIds"]
        confusing = item["confusingChunkIds"]
        referenced = set(relevant) | set(acceptable) | set(confusing)
        referenced.update(chunk_id for group in groups for chunk_id in group["chunkIds"])
        if not referenced <= set(chunk_ids):
            raise ValueError(f"unknown chunk reference in {item['caseId']}")
        if set(acceptable) & set(confusing):
            raise ValueError(f"acceptable/confusing overlap in {item['caseId']}")
        if item["answerable"]:
            if not relevant or not acceptable or not groups or not required_sources:
                raise ValueError(f"answerable case has empty positive evidence: {item['caseId']}")
            if not set(relevant) <= set(acceptable):
                raise ValueError(f"strict relevance is not acceptable: {item['caseId']}")
            grouped = {chunk_id for group in groups for chunk_id in group["chunkIds"]}
            if grouped != set(acceptable):
                raise ValueError(f"evidence groups do not close over acceptable evidence: {item['caseId']}")
            actual_sources = sorted({source_by_chunk[chunk_id] for chunk_id in acceptable})
            if actual_sources != required_sources:
                raise ValueError(f"required sources do not close over evidence: {item['caseId']}")
            if any(group["chunkIds"] != sorted(group["chunkIds"]) for group in groups):
                raise ValueError(f"group chunk ids are not sorted: {item['caseId']}")
            has_alternative_group |= any(len(group["chunkIds"]) >= 2 for group in groups)
        else:
            unanswerable_count += 1
            if relevant or acceptable or groups or required_sources:
                raise ValueError(f"unanswerable case contains positive evidence: {item['caseId']}")

    if unanswerable_count < 2:
        raise ValueError("at least two unanswerable cases are required")
    if not has_alternative_group:
        raise ValueError("an equivalent alternative evidence group is required")
    effects = {
        item["retrievalExpectations"]["rerankEffect"]
        for item in cases
        if item["answerable"]
    }
    if "improves" not in effects or "degrades" not in effects:
        raise ValueError("dataset must contain measured rerank improvement and degradation")


def json_line(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write_lf(path: Path, text: str) -> None:
    path.write_bytes(text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8"))


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    write_lf(path, "".join(json_line(row) + "\n" for row in rows))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def readme_text() -> str:
    return f"""# public-small-v1

`public-small-v1` is a deterministic, fully synthetic retrieval and reranking fixture. It contains neutral fictional documentation for Atlas, Nimbus, Orchid, Quartz, and Zephyr.

## Contents

- `corpus.jsonl`: {len(CHUNKS)} original synthetic chunks sorted by `chunkId`.
- `cases.jsonl`: {len(CASES)} cases sorted by `caseId`, including answerable, multi-evidence, alternative-evidence, disambiguation, rerank-change, and abstention cases.
- `embeddings.jsonl`: frozen {EMBEDDING_DIMENSION}-dimensional unit vectors for every chunk and query.
- `manifest.json`: dataset contract, counts, generation settings, and data-file digests.
- `checksums.sha256`: SHA-256 digests, including the manifest digest.
- `SOURCES_AND_LICENSE.md`: synthetic-source and license boundary.

## Generate

From the repository root:

```powershell
python -X utf8 backend/evals/scripts/generate_public_small_v1.py
```

The generator uses Python standard library only, seed `{SEED}`, sorted JSON keys, stable row order, UTF-8, and LF line endings. It emits no timestamp. Repeated runs with the same generator are byte-identical.

## Retrieval contract

- Index: exact cosine over the frozen unit vectors.
- Candidate count: `{CANDIDATE_K}`.
- Final count after local lexical reranking: `{TOP_K}`.
- Vector ties: ascending `chunkId`.
- Local rerank score: `{VECTOR_WEIGHT} * cosine + {LEXICAL_WEIGHT} * query-term coverage`; rerank ties use ascending `chunkId`, then `documentId`.
- Each case records the expected exact-cosine candidates and reranked top results calculated by the generator.

The vectors are intentionally small and imperfect. This fixture tests deterministic evaluation plumbing and known ranking behavior; it is not evidence of production retrieval quality.

## Evidence semantics

- `relevantChunkIds` is the strict labeled relevance set.
- `acceptableChunkIds` contains evidence accepted by the deterministic grader.
- Every object in `requiredEvidenceGroups` is a required group; one chunk from each group satisfies evidence coverage.
- Multiple chunk IDs inside one group are genuinely equivalent alternatives for that fact.
- `requiredSourceIds` is exactly the source closure of acceptable evidence.
- Unanswerable cases have empty positive evidence fields and should lead to abstention.

`manifest.json` cannot safely contain its own digest because that would be self-referential. Its SHA-256 is therefore recorded in `checksums.sha256`; all non-self-referential core data digests are recorded inside the manifest.
"""


def sources_and_license_text() -> str:
    return """# Sources and license

## Provenance

Every source name, passage, query, evidence label, and vector in this dataset was created as original synthetic material for this generation run. Atlas, Nimbus, Orchid, Quartz, and Zephyr are fictional systems used only inside this fixture.

No passage was copied, translated, summarized, or adapted from private evaluation data, personal notes, proprietary documentation, or a private knowledge base. The dataset has no external source dependency.

## Source registry

| Source ID | Fictional title | Origin |
|---|---|---|
| `source-atlas-cache-guide` | Atlas Cache Guide | Original synthetic content |
| `source-nimbus-queue-manual` | Nimbus Queue Manual | Original synthetic content |
| `source-orchid-deployment-notes` | Orchid Deployment Notes | Original synthetic content |
| `source-quartz-storage-spec` | Quartz Storage Specification | Original synthetic content |
| `source-zephyr-observability-runbook` | Zephyr Observability Runbook | Original synthetic content |

## License boundary

The dataset content in `corpus.jsonl`, `cases.jsonl`, `embeddings.jsonl`, and `manifest.json`, plus this dataset documentation, is marked `CC0-1.0`.

The generator code in `backend/evals/scripts/generate_public_small_v1.py` remains under the repository's code license. The CC0 marker for dataset content does not relicense unrelated repository code.
"""


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    chunks = sorted(CHUNKS, key=lambda item: item["chunkId"])
    cases = sorted(CASES, key=lambda item: item["caseId"])
    chunk_vectors = {
        item["chunkId"]: vector_from_coordinates(str(item["chunkId"]), item["_coordinates"])
        for item in chunks
    }
    query_vectors = {
        item["caseId"]: blend_vector(
            item["caseId"], QUERY_VECTOR_PLANS[item["caseId"]], chunk_vectors
        )
        for item in cases
    }
    add_retrieval_expectations(cases, chunks, chunk_vectors, query_vectors)
    validate_dataset(chunks, cases, chunk_vectors, query_vectors)

    corpus_rows = [
        {key: value for key, value in item.items() if not key.startswith("_")}
        for item in chunks
    ]
    embedding_rows = [
        {
            "embeddingId": f"chunk:{item['chunkId']}",
            "itemType": "chunk",
            "chunkId": item["chunkId"],
            "vector": chunk_vectors[item["chunkId"]],
        }
        for item in chunks
    ] + [
        {
            "embeddingId": f"query:{item['caseId']}",
            "itemType": "query",
            "caseId": item["caseId"],
            "vector": query_vectors[item["caseId"]],
        }
        for item in cases
    ]
    embedding_rows.sort(key=lambda item: item["embeddingId"])

    corpus_path = OUTPUT_DIR / "corpus.jsonl"
    cases_path = OUTPUT_DIR / "cases.jsonl"
    embeddings_path = OUTPUT_DIR / "embeddings.jsonl"
    readme_path = OUTPUT_DIR / "README.md"
    sources_path = OUTPUT_DIR / "SOURCES_AND_LICENSE.md"
    manifest_path = OUTPUT_DIR / "manifest.json"
    checksums_path = OUTPUT_DIR / "checksums.sha256"

    write_jsonl(corpus_path, corpus_rows)
    write_jsonl(cases_path, cases)
    write_jsonl(embeddings_path, embedding_rows)
    write_lf(readme_path, readme_text())
    write_lf(sources_path, sources_and_license_text())

    effect_counts: dict[str, int] = {}
    for item in cases:
        effect = item["retrievalExpectations"]["rerankEffect"]
        effect_counts[effect] = effect_counts.get(effect, 0) + 1

    manifest = {
        "datasetId": DATASET_ID,
        "schemaVersion": SCHEMA_VERSION,
        "datasetVersion": DATASET_VERSION,
        "seed": SEED,
        "embeddingDimension": EMBEDDING_DIMENSION,
        "candidateK": CANDIDATE_K,
        "topK": TOP_K,
        "indexType": "exact-cosine",
        "tieBreaker": "score-desc,chunkId-asc",
        "license": "CC0-1.0",
        "sourceType": "synthetic-original",
        "expectedChunkCount": len(chunks),
        "expectedCaseCount": len(cases),
        "files": {
            "corpus": {
                "file": "corpus.jsonl",
                "sha256": sha256(corpus_path),
                "records": len(corpus_rows),
            },
            "cases": {
                "file": "cases.jsonl",
                "sha256": sha256(cases_path),
                "records": len(cases),
            },
            "embeddings": {
                "file": "embeddings.jsonl",
                "sha256": sha256(embeddings_path),
                "records": len(embedding_rows),
            },
        },
    }
    write_lf(manifest_path, json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")

    checksum_targets = [
        cases_path,
        corpus_path,
        embeddings_path,
        manifest_path,
    ]
    checksum_lines = [
        f"{sha256(path)}  {path.name}\n" for path in sorted(checksum_targets, key=lambda value: value.name)
    ]
    write_lf(checksums_path, "".join(checksum_lines))

    print(
        json.dumps(
            {
                "datasetId": DATASET_ID,
                "outputDir": f"backend/evals/datasets/{DATASET_ID}",
                "chunkCount": len(chunks),
                "caseCount": len(cases),
                "embeddingCount": len(embedding_rows),
                "rerankEffectCounts": dict(sorted(effect_counts.items())),
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
