#!/usr/bin/env python3
"""Fail-closed tiered regression comparator for sanitized RAG eval evidence."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import math
import os
import re
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Sequence


RESULT_SCHEMA = "rag-tiered-regression-result/v1"
BASELINE_SCHEMA = "rag-tiered-regression-baseline/v1"
POLICY_SCHEMA = "rag-tiered-regression-policy/v1"
RESULT_SCHEMA_V2 = "rag-tiered-regression-result/v2"
BASELINE_SCHEMA_V2 = "rag-tiered-regression-baseline/v2"
POLICY_SCHEMA_V2 = "rag-tiered-regression-policy/v2"
CANDIDATE_SCHEMA_V2 = "rag-tiered-regression-candidate/v2"

PASS = 0
OPERATION_ERROR = 1
CODE_REGRESSION = 2
DATA_MODEL_DRIFT = 3
INFRA_FAILURE = 4
OWNER_DECISION_DEFERRED = 5

VERDICTS = {
    PASS: "PASS",
    OPERATION_ERROR: "OPERATION_ERROR",
    CODE_REGRESSION: "CODE_REGRESSION",
    DATA_MODEL_DRIFT: "DATA_MODEL_DRIFT",
    INFRA_FAILURE: "INFRA_FAILURE",
    OWNER_DECISION_DEFERRED: "OWNER_DECISION_DEFERRED",
}

CONFIGURATION_FIELDS = {
    "embedding",
    "dimension",
    "vectorStore",
    "reranker",
    "candidateK",
    "topK",
    "activeVersionIds",
}
OVERALL_COUNT_FIELDS = {
    "caseCount",
    "answerableCaseCount",
    "unanswerableCaseCount",
}
OVERALL_METRIC_FIELDS = {
    "recallAt1",
    "recallAt3",
    "recallAt5",
    "mrr",
    "ndcgAt5",
    "noAnswerFalsePositiveRate",
    "staleVersionLeakCount",
}
ANSWERABLE_METRIC_FIELDS = {
    "recallAt1",
    "recallAt3",
    "recallAt5",
    "reciprocalRank",
    "ndcgAt5",
    "staleVersionLeakCount",
}
UNANSWERABLE_METRIC_FIELDS = {
    "returnedAnyChunk",
    "staleVersionLeakCount",
}
V2_METRIC_FIELDS = {
    "recallAt10",
    "sourceCoverageAt10",
    "evidenceGroupCoverageAt10",
}
SUPPORTED_RULES = {"NOT_LOWER", "NOT_HIGHER", "FALSE_OR_EQUAL"}
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SAFE_SEVERITY = re.compile(r"^[A-Z][A-Z0-9_]{0,31}$")
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
PLAIN_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class OperationError(Exception):
    """The command or its control inputs cannot be used safely."""


class EvidenceError(Exception):
    """Execution or frozen baseline evidence is not trustworthy."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class DuplicateKeyError(ValueError):
    """A JSON object contains duplicate keys."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise OperationError("invalid arguments")


@dataclasses.dataclass(frozen=True)
class Config:
    baseline: Path
    candidate: Path
    policy: Path
    output: Path


@dataclasses.dataclass(frozen=True)
class CandidateIdentity:
    evaluation_type: str
    dataset_version: str
    configuration_fingerprint: str
    case_set_fingerprint: str
    dataset_input_fingerprint: str
    case_ids: tuple[str, ...]
    case_input_fingerprints: Mapping[str, str]
    answerability: Mapping[str, str]
    top_k: int
    configuration_has_extra_fields: bool
    severities: Mapping[str, str] = dataclasses.field(default_factory=dict)


@dataclasses.dataclass(frozen=True)
class GateOutcome:
    exit_code: int
    report: Mapping[str, Any]


def _canonical_bytes(value: Any) -> bytes:
    try:
        text = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise EvidenceError("NON_CANONICAL_JSON_EVIDENCE") from exc
    return text.encode("utf-8")


def _fingerprint(domain: str, value: Any) -> str:
    digest = hashlib.sha256(domain.encode("ascii") + b"\0")
    digest.update(_canonical_bytes(value))
    return "sha256:" + digest.hexdigest()


def _sha256_file(path: Path, error_type: type[Exception]) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except (OSError, ValueError) as exc:
        if error_type is EvidenceError:
            raise EvidenceError("EVIDENCE_READ_FAILED") from exc
        raise OperationError("control input read failed") from exc
    return digest.hexdigest()


def _reject_constant(_: str) -> None:
    raise ValueError("non-finite JSON constant")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(key)
        result[key] = value
    return result


def _read_json(path: Path, *, evidence: bool) -> tuple[Any, str]:
    error_type: type[Exception] = EvidenceError if evidence else OperationError
    try:
        if not path.is_file():
            raise FileNotFoundError
        raw = path.read_text(encoding="utf-8")
        value = json.loads(
            raw,
            parse_constant=_reject_constant,
            object_pairs_hook=_unique_object,
        )
        _assert_all_numbers_finite(value)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError) as exc:
        if evidence:
            raise EvidenceError("EVIDENCE_JSON_INVALID") from exc
        raise OperationError("control JSON invalid") from exc
    return value, _sha256_file(path, error_type)


def _schema_version(payload: Any, artifact: str, *, evidence: bool) -> str:
    if not isinstance(payload, dict):
        if evidence:
            raise EvidenceError(f"{artifact.upper()}_SCHEMA_VERSION_MISMATCH")
        raise OperationError("policy schema invalid")
    schema = payload.get("schema")
    expected = {
        "policy": {POLICY_SCHEMA: "v1", POLICY_SCHEMA_V2: "v2"},
        "baseline": {BASELINE_SCHEMA: "v1", BASELINE_SCHEMA_V2: "v2"},
        "candidate": {None: "v1", CANDIDATE_SCHEMA_V2: "v2"},
    }
    version = expected.get(artifact, {}).get(schema)
    if version is None:
        if evidence:
            raise EvidenceError(f"{artifact.upper()}_SCHEMA_VERSION_MISMATCH")
        raise OperationError("policy schema invalid")
    return version


def _assert_all_numbers_finite(value: Any) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("non-finite number")
    if isinstance(value, dict):
        for child in value.values():
            _assert_all_numbers_finite(child)
    elif isinstance(value, list):
        for child in value:
            _assert_all_numbers_finite(child)


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _safe_id(value: Any, code: str) -> str:
    if not isinstance(value, str) or SAFE_ID.fullmatch(value) is None:
        raise EvidenceError(code)
    return value


def _safe_control_id(value: Any) -> str:
    if not isinstance(value, str) or SAFE_ID.fullmatch(value) is None:
        raise OperationError("control identifier invalid")
    return value


def _require_object(value: Any, code: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(code)
    return value


def _require_list(value: Any, code: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvidenceError(code)
    return value


def _require_nonempty_string(value: Any, code: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvidenceError(code)
    return value


def _require_hash(value: Any, code: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise EvidenceError(code)
    return value


def _require_plain_hash(value: Any) -> str:
    if not isinstance(value, str) or PLAIN_SHA256.fullmatch(value) is None:
        raise OperationError("control hash invalid")
    return value


def _require_count(value: Any, code: str) -> int:
    if not _is_int(value) or value < 0:
        raise EvidenceError(code)
    return value


def _require_ratio(value: Any, code: str) -> float | int:
    if not _is_number(value) or not 0.0 <= float(value) <= 1.0:
        raise EvidenceError(code)
    return value


def _configuration_identity(configuration: Any) -> tuple[dict[str, Any], bool]:
    config = _require_object(configuration, "CONFIGURATION_MISSING_OR_INVALID")
    missing = CONFIGURATION_FIELDS - set(config)
    if missing:
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    embedding = _require_nonempty_string(config["embedding"], "CONFIGURATION_INCOMPLETE")
    vector_store = _require_nonempty_string(config["vectorStore"], "CONFIGURATION_INCOMPLETE")
    reranker = _require_nonempty_string(config["reranker"], "CONFIGURATION_INCOMPLETE")
    dimension = config["dimension"]
    candidate_k = config["candidateK"]
    top_k = config["topK"]
    if not _is_int(dimension) or dimension <= 0:
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    if not _is_int(candidate_k) or candidate_k <= 0:
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    if not _is_int(top_k) or top_k <= 0 or top_k > candidate_k:
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    active_ids = _require_list(config["activeVersionIds"], "CONFIGURATION_INCOMPLETE")
    if not active_ids or any(not _is_int(item) for item in active_ids):
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    if len(set(active_ids)) != len(active_ids):
        raise EvidenceError("CONFIGURATION_INCOMPLETE")
    identity = {
        "embedding": embedding,
        "dimension": dimension,
        "vectorStore": vector_store,
        "reranker": reranker,
        "candidateK": candidate_k,
        "topK": top_k,
        "activeVersionIds": sorted(active_ids),
    }
    return identity, set(config) != CONFIGURATION_FIELDS


def _case_input_identity(case: Mapping[str, Any]) -> tuple[str, str, str]:
    case_id = _safe_id(case.get("caseId"), "CASE_ID_INVALID")
    answerability = case.get("answerability")
    if answerability not in {"ANSWERABLE", "UNANSWERABLE"}:
        raise EvidenceError("CASE_ANSWERABILITY_INVALID")
    query = _require_nonempty_string(case.get("query"), "CASE_INPUT_INCOMPLETE")
    tags = _require_list(case.get("tags"), "CASE_INPUT_INCOMPLETE")
    relevant = _require_list(case.get("relevantChunkIds"), "CASE_INPUT_INCOMPLETE")
    if any(not isinstance(tag, str) or not tag for tag in tags):
        raise EvidenceError("CASE_INPUT_INCOMPLETE")
    if any(not _is_int(item) for item in relevant):
        raise EvidenceError("CASE_INPUT_INCOMPLETE")
    if len(set(tags)) != len(tags) or len(set(relevant)) != len(relevant):
        raise EvidenceError("CASE_INPUT_INCOMPLETE")
    if answerability == "ANSWERABLE" and not relevant:
        raise EvidenceError("CASE_INPUT_INCOMPLETE")
    identity = {
        "caseId": case_id,
        "query": query,
        "answerability": answerability,
        "tags": sorted(tags),
        "relevantChunkIds": sorted(relevant),
    }
    return case_id, answerability, _fingerprint("rag-tiered-regression-case-input/v1", identity)


def inspect_candidate_identity(payload: Any) -> CandidateIdentity:
    root = _require_object(payload, "CANDIDATE_ROOT_INVALID")
    evaluation_type = _safe_id(root.get("evaluationType"), "EVALUATION_TYPE_INVALID")
    dataset_version = _safe_id(root.get("datasetVersion"), "DATASET_VERSION_INVALID")
    configuration, extra_fields = _configuration_identity(root.get("configuration"))
    cases = _require_list(root.get("cases"), "CASES_MISSING_OR_INVALID")
    if not cases:
        raise EvidenceError("CASES_MISSING_OR_INVALID")
    fingerprints: dict[str, str] = {}
    answerability: dict[str, str] = {}
    for raw_case in cases:
        case = _require_object(raw_case, "CASE_INVALID")
        case_id, case_answerability, fingerprint = _case_input_identity(case)
        if case_id in fingerprints:
            raise EvidenceError("DUPLICATE_CASE_ID")
        fingerprints[case_id] = fingerprint
        answerability[case_id] = case_answerability
    case_ids = tuple(sorted(fingerprints))
    case_set_fingerprint = _fingerprint("rag-tiered-regression-case-set/v1", list(case_ids))
    dataset_input_fingerprint = _fingerprint(
        "rag-tiered-regression-dataset-input/v1",
        [{"caseId": case_id, "inputFingerprint": fingerprints[case_id]} for case_id in case_ids],
    )
    return CandidateIdentity(
        evaluation_type=evaluation_type,
        dataset_version=dataset_version,
        configuration_fingerprint=_fingerprint(
            "rag-tiered-regression-configuration/v1", configuration
        ),
        case_set_fingerprint=case_set_fingerprint,
        dataset_input_fingerprint=dataset_input_fingerprint,
        case_ids=case_ids,
        case_input_fingerprints=fingerprints,
        answerability=answerability,
        top_k=configuration["topK"],
        configuration_has_extra_fields=extra_fields,
    )


def _severity(value: Any, code: str) -> str:
    if not isinstance(value, str) or SAFE_SEVERITY.fullmatch(value) is None:
        raise EvidenceError(code)
    return value


def _v2_case_input_identity(
    case: Mapping[str, Any],
) -> tuple[str, str, str, str]:
    case_id = _safe_id(case.get("caseId"), "CASE_ID_INVALID")
    answerability = case.get("answerability")
    if answerability not in {"ANSWERABLE", "UNANSWERABLE"}:
        raise EvidenceError("CASE_ANSWERABILITY_INVALID")
    severity = _severity(case.get("severity"), "V2_CASE_INPUT_INCOMPLETE")
    query = _require_nonempty_string(case.get("query"), "V2_CASE_INPUT_INCOMPLETE")
    tags = _require_list(case.get("tags"), "V2_CASE_INPUT_INCOMPLETE")
    if any(not isinstance(tag, str) or not tag for tag in tags) or len(set(tags)) != len(tags):
        raise EvidenceError("V2_CASE_INPUT_INCOMPLETE")
    raw_truth = _require_list(case.get("relevantEvidence"), "V2_CASE_INPUT_INCOMPLETE")
    truth: list[dict[str, Any]] = []
    seen_chunks: set[int] = set()
    for raw_item in raw_truth:
        item = _require_object(raw_item, "V2_CASE_INPUT_INCOMPLETE")
        if set(item) != {"chunkId", "sourceId", "evidenceGroupId"}:
            raise EvidenceError("V2_CASE_INPUT_INCOMPLETE")
        chunk_id = item.get("chunkId")
        if not _is_int(chunk_id) or chunk_id in seen_chunks:
            raise EvidenceError("V2_CASE_INPUT_INCOMPLETE")
        seen_chunks.add(chunk_id)
        truth.append(
            {
                "chunkId": chunk_id,
                "sourceId": _safe_id(item.get("sourceId"), "V2_CASE_INPUT_INCOMPLETE"),
                "evidenceGroupId": _safe_id(
                    item.get("evidenceGroupId"), "V2_CASE_INPUT_INCOMPLETE"
                ),
            }
        )
    if (answerability == "ANSWERABLE") != bool(truth):
        raise EvidenceError("V2_CASE_INPUT_INCOMPLETE")
    identity = {
        "caseId": case_id,
        "query": query,
        "answerability": answerability,
        "severity": severity,
        "tags": sorted(tags),
        "relevantEvidence": sorted(
            truth,
            key=lambda item: (
                item["chunkId"], item["sourceId"], item["evidenceGroupId"]
            ),
        ),
    }
    return (
        case_id,
        answerability,
        severity,
        _fingerprint("rag-tiered-regression-case-input/v2", identity),
    )


def inspect_candidate_identity_v2(payload: Any) -> CandidateIdentity:
    root = _require_object(payload, "CANDIDATE_ROOT_INVALID")
    if root.get("schema") != CANDIDATE_SCHEMA_V2:
        raise EvidenceError("CANDIDATE_SCHEMA_VERSION_MISMATCH")
    evaluation_type = _safe_id(root.get("evaluationType"), "EVALUATION_TYPE_INVALID")
    dataset_version = _safe_id(root.get("datasetVersion"), "DATASET_VERSION_INVALID")
    configuration, extra_fields = _configuration_identity(root.get("configuration"))
    cases = _require_list(root.get("cases"), "CASES_MISSING_OR_INVALID")
    if not cases:
        raise EvidenceError("CASES_MISSING_OR_INVALID")
    fingerprints: dict[str, str] = {}
    answerability: dict[str, str] = {}
    severities: dict[str, str] = {}
    for raw_case in cases:
        case = _require_object(raw_case, "CASE_INVALID")
        case_id, case_answerability, severity, fingerprint = _v2_case_input_identity(case)
        if case_id in fingerprints:
            raise EvidenceError("DUPLICATE_CASE_ID")
        fingerprints[case_id] = fingerprint
        answerability[case_id] = case_answerability
        severities[case_id] = severity
    case_ids = tuple(sorted(fingerprints))
    case_set_fingerprint = _fingerprint("rag-tiered-regression-case-set/v2", list(case_ids))
    dataset_input_fingerprint = _fingerprint(
        "rag-tiered-regression-dataset-input/v2",
        [{"caseId": case_id, "inputFingerprint": fingerprints[case_id]} for case_id in case_ids],
    )
    return CandidateIdentity(
        evaluation_type=evaluation_type,
        dataset_version=dataset_version,
        configuration_fingerprint=_fingerprint(
            "rag-tiered-regression-configuration/v2", configuration
        ),
        case_set_fingerprint=case_set_fingerprint,
        dataset_input_fingerprint=dataset_input_fingerprint,
        case_ids=case_ids,
        case_input_fingerprints=fingerprints,
        answerability=answerability,
        top_k=configuration["topK"],
        configuration_has_extra_fields=extra_fields,
        severities=severities,
    )


def _validate_metric_map(metrics: Any, answerability: str) -> dict[str, Any]:
    metric_map = _require_object(metrics, "CASE_METRICS_INVALID")
    expected = ANSWERABLE_METRIC_FIELDS if answerability == "ANSWERABLE" else UNANSWERABLE_METRIC_FIELDS
    if set(metric_map) != expected:
        raise EvidenceError("CASE_METRICS_INCOMPLETE")
    for name, value in metric_map.items():
        if name == "staleVersionLeakCount":
            _require_count(value, "CASE_METRIC_INVALID")
        elif name == "returnedAnyChunk":
            if not isinstance(value, bool):
                raise EvidenceError("CASE_METRIC_INVALID")
        else:
            _require_ratio(value, "CASE_METRIC_INVALID")
    return metric_map


def _validate_overall(overall: Any) -> dict[str, Any]:
    values = _require_object(overall, "OVERALL_MISSING_OR_INVALID")
    if set(values) != OVERALL_COUNT_FIELDS | OVERALL_METRIC_FIELDS:
        raise EvidenceError("OVERALL_INCOMPLETE")
    for name in OVERALL_COUNT_FIELDS:
        _require_count(values[name], "OVERALL_COUNT_INVALID")
    for name in OVERALL_METRIC_FIELDS:
        if name == "staleVersionLeakCount":
            _require_count(values[name], "OVERALL_METRIC_INVALID")
        else:
            _require_ratio(values[name], "OVERALL_METRIC_INVALID")
    return values


def _validate_returned_ids(returned: Any, top_k: int) -> list[int]:
    items = _require_list(returned, "CASE_EXECUTION_INCOMPLETE")
    if len(items) > top_k:
        raise EvidenceError("CASE_EXECUTION_INCOMPLETE")
    ranked: list[int] = []
    seen: set[int] = set()
    for raw_item in items:
        item = _require_object(raw_item, "RETURNED_EVIDENCE_INVALID")
        chunk_id = item.get("chunkId")
        if not _is_int(chunk_id):
            raise EvidenceError("RETURNED_EVIDENCE_INVALID")
        if not _is_number(item.get("score")):
            raise EvidenceError("RETURNED_EVIDENCE_INVALID")
        if not isinstance(item.get("title"), str):
            raise EvidenceError("RETURNED_EVIDENCE_INVALID")
        if chunk_id not in seen:
            seen.add(chunk_id)
            ranked.append(chunk_id)
    return ranked


def _recall_at(ranked: Sequence[int], relevant: set[int], k: int) -> float:
    if not relevant:
        return 0.0
    return sum(chunk_id in relevant for chunk_id in ranked[:k]) / len(relevant)


def _reciprocal_rank(ranked: Sequence[int], relevant: set[int], k: int) -> float:
    for index, chunk_id in enumerate(ranked[:k]):
        if chunk_id in relevant:
            return 1.0 / (index + 1.0)
    return 0.0


def _ndcg_at(ranked: Sequence[int], relevant: set[int], k: int) -> float:
    if not relevant:
        return 0.0
    dcg = sum(
        1.0 / math.log2(index + 2.0)
        for index, chunk_id in enumerate(ranked[:k])
        if chunk_id in relevant
    )
    idcg = sum(1.0 / math.log2(index + 2.0) for index in range(min(k, len(relevant))))
    return 0.0 if idcg == 0.0 else dcg / idcg


def _assert_metric_matches(actual: Any, expected: Any, code: str) -> None:
    if isinstance(expected, bool):
        matches = actual is expected
    elif _is_int(expected):
        matches = _is_int(actual) and actual == expected
    else:
        matches = _is_number(actual) and math.isclose(
            float(actual), float(expected), rel_tol=1e-12, abs_tol=1e-12
        )
    if not matches:
        raise EvidenceError(code)


def _validate_case_metric_consistency(
    case: Mapping[str, Any], metrics: Mapping[str, Any], ranked: Sequence[int]
) -> None:
    answerability = case["answerability"]
    if answerability == "UNANSWERABLE":
        _assert_metric_matches(
            metrics["returnedAnyChunk"], bool(ranked[:5]), "CASE_METRIC_INCONSISTENT"
        )
        return
    relevant = set(case["relevantChunkIds"])
    expected = {
        "recallAt1": _recall_at(ranked, relevant, 1),
        "recallAt3": _recall_at(ranked, relevant, 3),
        "recallAt5": _recall_at(ranked, relevant, 5),
        "reciprocalRank": _reciprocal_rank(ranked, relevant, 5),
        "ndcgAt5": _ndcg_at(ranked, relevant, 5),
    }
    for name, value in expected.items():
        _assert_metric_matches(metrics[name], value, "CASE_METRIC_INCONSISTENT")


def _validate_overall_consistency(
    overall: Mapping[str, Any], cases: Sequence[Mapping[str, Any]], code: str
) -> None:
    answerable = [case for case in cases if case["answerability"] == "ANSWERABLE"]
    unanswerable = [case for case in cases if case["answerability"] == "UNANSWERABLE"]

    def average(metric: str, selected: Sequence[Mapping[str, Any]]) -> float:
        if not selected:
            return 0.0
        return sum(float(case["metrics"][metric]) for case in selected) / len(selected)

    expected = {
        "caseCount": len(cases),
        "answerableCaseCount": len(answerable),
        "unanswerableCaseCount": len(unanswerable),
        "recallAt1": average("recallAt1", answerable),
        "recallAt3": average("recallAt3", answerable),
        "recallAt5": average("recallAt5", answerable),
        "mrr": average("reciprocalRank", answerable),
        "ndcgAt5": average("ndcgAt5", answerable),
        "noAnswerFalsePositiveRate": average("returnedAnyChunk", unanswerable),
        "staleVersionLeakCount": sum(
            case["metrics"]["staleVersionLeakCount"] for case in cases
        ),
    }
    for name, value in expected.items():
        _assert_metric_matches(overall[name], value, code)


def validate_candidate_evidence(payload: Any, identity: CandidateIdentity) -> None:
    root = _require_object(payload, "CANDIDATE_ROOT_INVALID")
    _require_nonempty_string(root.get("evidenceBoundary"), "CANDIDATE_METADATA_INCOMPLETE")
    executed_at = _require_nonempty_string(root.get("executedAt"), "CANDIDATE_METADATA_INCOMPLETE")
    _require_nonempty_string(root.get("codeRevision"), "CANDIDATE_METADATA_INCOMPLETE")
    _require_nonempty_string(root.get("worktreeFingerprint"), "CANDIDATE_METADATA_INCOMPLETE")
    try:
        datetime.fromisoformat(executed_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError("CANDIDATE_TIMESTAMP_INVALID") from exc
    overall = _validate_overall(root.get("overall"))
    cases = _require_list(root.get("cases"), "CASES_MISSING_OR_INVALID")
    answerable_count = 0
    normalized_cases: list[dict[str, Any]] = []
    for raw_case in cases:
        case = _require_object(raw_case, "CASE_INVALID")
        case_id = _safe_id(case.get("caseId"), "CASE_ID_INVALID")
        answerability = identity.answerability[case_id]
        if answerability == "ANSWERABLE":
            answerable_count += 1
        metrics = _validate_metric_map(case.get("metrics"), answerability)
        latency = case.get("latencyMs")
        if not _is_int(latency) or latency < 0:
            raise EvidenceError("CASE_EXECUTION_INCOMPLETE")
        ranked = _validate_returned_ids(case.get("returned"), identity.top_k)
        _validate_case_metric_consistency(case, metrics, ranked)
        normalized_cases.append({"answerability": answerability, "metrics": metrics})
    if overall["caseCount"] != len(cases):
        raise EvidenceError("CASE_COUNT_MISMATCH")
    if overall["answerableCaseCount"] != answerable_count:
        raise EvidenceError("CASE_COUNT_MISMATCH")
    if overall["unanswerableCaseCount"] != len(cases) - answerable_count:
        raise EvidenceError("CASE_COUNT_MISMATCH")
    _validate_overall_consistency(overall, normalized_cases, "OVERALL_METRIC_INCONSISTENT")


def _validate_v2_metric_map(metrics: Any, code: str) -> dict[str, Any]:
    values = _require_object(metrics, code)
    if set(values) != V2_METRIC_FIELDS:
        raise EvidenceError(code)
    for value in values.values():
        _require_ratio(value, code)
    return values


def _validate_v2_overall(overall: Any) -> dict[str, Any]:
    values = _require_object(overall, "V2_OVERALL_INVALID")
    if set(values) != OVERALL_COUNT_FIELDS | V2_METRIC_FIELDS:
        raise EvidenceError("V2_OVERALL_INVALID")
    for name in OVERALL_COUNT_FIELDS:
        _require_count(values[name], "V2_OVERALL_INVALID")
    for name in V2_METRIC_FIELDS:
        _require_ratio(values[name], "V2_OVERALL_INVALID")
    return values


def _validate_v2_returned(returned: Any, top_k: int) -> list[dict[str, Any]]:
    items = _require_list(returned, "V2_RETURNED_EVIDENCE_INVALID")
    if len(items) > top_k:
        raise EvidenceError("V2_RETURNED_EVIDENCE_INVALID")
    normalized: list[dict[str, Any]] = []
    seen: set[int] = set()
    expected = {"chunkId", "score", "title", "sourceId", "evidenceGroupId"}
    for raw_item in items:
        item = _require_object(raw_item, "V2_RETURNED_EVIDENCE_INVALID")
        if set(item) != expected:
            raise EvidenceError("V2_RETURNED_EVIDENCE_INVALID")
        chunk_id = item.get("chunkId")
        if not _is_int(chunk_id) or chunk_id in seen:
            raise EvidenceError("V2_RETURNED_EVIDENCE_INVALID")
        seen.add(chunk_id)
        if not _is_number(item.get("score")) or not isinstance(item.get("title"), str):
            raise EvidenceError("V2_RETURNED_EVIDENCE_INVALID")
        normalized.append(
            {
                "chunkId": chunk_id,
                "sourceId": _safe_id(
                    item.get("sourceId"), "V2_RETURNED_EVIDENCE_INVALID"
                ),
                "evidenceGroupId": _safe_id(
                    item.get("evidenceGroupId"), "V2_RETURNED_EVIDENCE_INVALID"
                ),
            }
        )
    return normalized


def recompute_v2_case_metrics(
    case: Mapping[str, Any], returned: Sequence[Mapping[str, Any]]
) -> dict[str, float]:
    truth_items = _require_list(case.get("relevantEvidence"), "V2_CASE_INPUT_INCOMPLETE")
    truth_by_chunk = {
        item["chunkId"]: (item["sourceId"], item["evidenceGroupId"])
        for item in truth_items
    }
    relevant_chunks = set(truth_by_chunk)
    relevant_sources = {source for source, _ in truth_by_chunk.values()}
    relevant_groups = {group for _, group in truth_by_chunk.values()}
    hit_chunks: set[int] = set()
    hit_sources: set[str] = set()
    hit_groups: set[str] = set()
    for item in returned[:10]:
        chunk_id = item["chunkId"]
        expected_identity = truth_by_chunk.get(chunk_id)
        if expected_identity is None:
            continue
        if (item["sourceId"], item["evidenceGroupId"]) != expected_identity:
            raise EvidenceError("V2_RETURNED_IDENTITY_MISMATCH")
        hit_chunks.add(chunk_id)
        hit_sources.add(item["sourceId"])
        hit_groups.add(item["evidenceGroupId"])

    def coverage(hits: set[Any], truth: set[Any]) -> float:
        return 0.0 if not truth else len(hits) / len(truth)

    return {
        "recallAt10": coverage(hit_chunks, relevant_chunks),
        "sourceCoverageAt10": coverage(hit_sources, relevant_sources),
        "evidenceGroupCoverageAt10": coverage(hit_groups, relevant_groups),
    }


def _validate_v2_overall_consistency(
    overall: Mapping[str, Any], cases: Sequence[Mapping[str, Any]], code: str
) -> None:
    answerable_count = sum(case["answerability"] == "ANSWERABLE" for case in cases)
    expected: dict[str, Any] = {
        "caseCount": len(cases),
        "answerableCaseCount": answerable_count,
        "unanswerableCaseCount": len(cases) - answerable_count,
    }
    for metric in V2_METRIC_FIELDS:
        expected[metric] = (
            0.0
            if not cases
            else sum(float(case["metrics"][metric]) for case in cases) / len(cases)
        )
    for name, value in expected.items():
        _assert_metric_matches(overall[name], value, code)


def validate_candidate_evidence_v2(payload: Any, identity: CandidateIdentity) -> None:
    root = _require_object(payload, "CANDIDATE_ROOT_INVALID")
    if identity.top_k < 10:
        raise EvidenceError("V2_TOP_K_TOO_SMALL")
    _require_nonempty_string(root.get("evidenceBoundary"), "CANDIDATE_METADATA_INCOMPLETE")
    executed_at = _require_nonempty_string(root.get("executedAt"), "CANDIDATE_METADATA_INCOMPLETE")
    _require_nonempty_string(root.get("codeRevision"), "CANDIDATE_METADATA_INCOMPLETE")
    _require_nonempty_string(root.get("worktreeFingerprint"), "CANDIDATE_METADATA_INCOMPLETE")
    try:
        datetime.fromisoformat(executed_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError("CANDIDATE_TIMESTAMP_INVALID") from exc
    overall = _validate_v2_overall(root.get("overall"))
    cases = _require_list(root.get("cases"), "CASES_MISSING_OR_INVALID")
    normalized_cases: list[dict[str, Any]] = []
    for raw_case in cases:
        case = _require_object(raw_case, "CASE_INVALID")
        case_id = _safe_id(case.get("caseId"), "CASE_ID_INVALID")
        metrics = _validate_v2_metric_map(
            case.get("metrics"), "V2_CASE_METRICS_INVALID"
        )
        latency = case.get("latencyMs")
        if not _is_int(latency) or latency < 0:
            raise EvidenceError("CASE_EXECUTION_INCOMPLETE")
        returned = _validate_v2_returned(case.get("returned"), identity.top_k)
        expected = recompute_v2_case_metrics(case, returned)
        for name, value in expected.items():
            _assert_metric_matches(metrics[name], value, "V2_CASE_METRIC_INCONSISTENT")
        normalized_cases.append(
            {"answerability": identity.answerability[case_id], "metrics": metrics}
        )
    _validate_v2_overall_consistency(
        overall, normalized_cases, "V2_OVERALL_METRIC_INCONSISTENT"
    )


def _validate_baseline(payload: Any) -> dict[str, Any]:
    root = _require_object(payload, "BASELINE_ROOT_INVALID")
    if root.get("schema") != BASELINE_SCHEMA:
        raise EvidenceError("BASELINE_SCHEMA_INVALID")
    if root.get("aggregateOnly") is not True or root.get("rawSamplesIncluded") is not False:
        raise EvidenceError("BASELINE_PRIVACY_CONTRACT_INVALID")
    baseline_id = _safe_id(root.get("baselineId"), "BASELINE_ID_INVALID")
    source_revision = _require_nonempty_string(root.get("sourceRevision"), "BASELINE_METADATA_INCOMPLETE")
    evaluation_type = _safe_id(root.get("evaluationType"), "BASELINE_METADATA_INCOMPLETE")
    dataset_version = _safe_id(root.get("datasetVersion"), "BASELINE_METADATA_INCOMPLETE")
    configuration_fingerprint = _require_hash(
        root.get("configurationFingerprint"), "BASELINE_CONFIGURATION_FINGERPRINT_INVALID"
    )
    case_set_fingerprint = _require_hash(
        root.get("caseSetFingerprint"), "BASELINE_CASE_SET_FINGERPRINT_INVALID"
    )
    dataset_input_fingerprint = _require_hash(
        root.get("datasetInputFingerprint"), "BASELINE_DATASET_FINGERPRINT_INVALID"
    )
    overall = _validate_overall(root.get("overall"))
    raw_cases = _require_list(root.get("cases"), "BASELINE_CASES_INVALID")
    if not raw_cases:
        raise EvidenceError("BASELINE_CASES_INVALID")
    cases: dict[str, dict[str, Any]] = {}
    fingerprints: dict[str, str] = {}
    answerable_count = 0
    for raw_case in raw_cases:
        case = _require_object(raw_case, "BASELINE_CASE_INVALID")
        if set(case) != {"caseId", "answerability", "inputFingerprint", "metrics"}:
            raise EvidenceError("BASELINE_CASE_INVALID")
        case_id = _safe_id(case.get("caseId"), "BASELINE_CASE_INVALID")
        if case_id in cases:
            raise EvidenceError("BASELINE_DUPLICATE_CASE_ID")
        answerability = case.get("answerability")
        if answerability not in {"ANSWERABLE", "UNANSWERABLE"}:
            raise EvidenceError("BASELINE_CASE_INVALID")
        if answerability == "ANSWERABLE":
            answerable_count += 1
        fingerprint = _require_hash(case.get("inputFingerprint"), "BASELINE_CASE_INVALID")
        metrics = _validate_metric_map(case.get("metrics"), answerability)
        cases[case_id] = {"answerability": answerability, "metrics": metrics}
        fingerprints[case_id] = fingerprint
    case_ids = tuple(sorted(cases))
    expected_case_set_fingerprint = _fingerprint(
        "rag-tiered-regression-case-set/v1", list(case_ids)
    )
    expected_dataset_fingerprint = _fingerprint(
        "rag-tiered-regression-dataset-input/v1",
        [{"caseId": case_id, "inputFingerprint": fingerprints[case_id]} for case_id in case_ids],
    )
    if case_set_fingerprint != expected_case_set_fingerprint:
        raise EvidenceError("BASELINE_CASE_SET_FINGERPRINT_INVALID")
    if dataset_input_fingerprint != expected_dataset_fingerprint:
        raise EvidenceError("BASELINE_DATASET_FINGERPRINT_INVALID")
    if overall["caseCount"] != len(cases):
        raise EvidenceError("BASELINE_COUNT_MISMATCH")
    if overall["answerableCaseCount"] != answerable_count:
        raise EvidenceError("BASELINE_COUNT_MISMATCH")
    if overall["unanswerableCaseCount"] != len(cases) - answerable_count:
        raise EvidenceError("BASELINE_COUNT_MISMATCH")
    _validate_overall_consistency(
        overall, list(cases.values()), "BASELINE_OVERALL_METRIC_INCONSISTENT"
    )
    return {
        "baselineId": baseline_id,
        "sourceRevision": source_revision,
        "evaluationType": evaluation_type,
        "datasetVersion": dataset_version,
        "configurationFingerprint": configuration_fingerprint,
        "caseSetFingerprint": case_set_fingerprint,
        "datasetInputFingerprint": dataset_input_fingerprint,
        "overall": overall,
        "cases": cases,
        "caseIds": case_ids,
        "caseInputFingerprints": fingerprints,
    }


def derive_critical_case_ids(cases: Mapping[str, Mapping[str, Any]]) -> tuple[str, ...]:
    return tuple(
        sorted(
            case_id
            for case_id, case in cases.items()
            if case.get("severity") == "CRITICAL"
        )
    )


def _validate_baseline_v2(payload: Any) -> dict[str, Any]:
    root = _require_object(payload, "BASELINE_ROOT_INVALID")
    if root.get("schema") != BASELINE_SCHEMA_V2:
        raise EvidenceError("BASELINE_SCHEMA_VERSION_MISMATCH")
    if root.get("aggregateOnly") is not True or root.get("rawSamplesIncluded") is not False:
        raise EvidenceError("BASELINE_PRIVACY_CONTRACT_INVALID")
    baseline_id = _safe_id(root.get("baselineId"), "BASELINE_ID_INVALID")
    source_revision = _require_nonempty_string(
        root.get("sourceRevision"), "BASELINE_METADATA_INCOMPLETE"
    )
    evaluation_type = _safe_id(
        root.get("evaluationType"), "BASELINE_METADATA_INCOMPLETE"
    )
    dataset_version = _safe_id(
        root.get("datasetVersion"), "BASELINE_METADATA_INCOMPLETE"
    )
    configuration_fingerprint = _require_hash(
        root.get("configurationFingerprint"), "BASELINE_CONFIGURATION_FINGERPRINT_INVALID"
    )
    case_set_fingerprint = _require_hash(
        root.get("caseSetFingerprint"), "BASELINE_CASE_SET_FINGERPRINT_INVALID"
    )
    dataset_input_fingerprint = _require_hash(
        root.get("datasetInputFingerprint"), "BASELINE_DATASET_FINGERPRINT_INVALID"
    )
    overall = _validate_v2_overall(root.get("overall"))
    raw_cases = _require_list(root.get("cases"), "BASELINE_CASES_INVALID")
    if not raw_cases:
        raise EvidenceError("BASELINE_CASES_INVALID")
    cases: dict[str, dict[str, Any]] = {}
    fingerprints: dict[str, str] = {}
    expected_fields = {
        "caseId",
        "answerability",
        "severity",
        "inputFingerprint",
        "metrics",
    }
    for raw_case in raw_cases:
        case = _require_object(raw_case, "BASELINE_CASE_INVALID")
        if set(case) != expected_fields:
            raise EvidenceError("BASELINE_CASE_INVALID")
        case_id = _safe_id(case.get("caseId"), "BASELINE_CASE_INVALID")
        if case_id in cases:
            raise EvidenceError("BASELINE_DUPLICATE_CASE_ID")
        answerability = case.get("answerability")
        if answerability not in {"ANSWERABLE", "UNANSWERABLE"}:
            raise EvidenceError("BASELINE_CASE_INVALID")
        severity = _severity(case.get("severity"), "BASELINE_CASE_INVALID")
        fingerprint = _require_hash(case.get("inputFingerprint"), "BASELINE_CASE_INVALID")
        metrics = _validate_v2_metric_map(case.get("metrics"), "BASELINE_CASE_INVALID")
        cases[case_id] = {
            "answerability": answerability,
            "severity": severity,
            "metrics": metrics,
        }
        fingerprints[case_id] = fingerprint
    case_ids = tuple(sorted(cases))
    expected_case_set_fingerprint = _fingerprint(
        "rag-tiered-regression-case-set/v2", list(case_ids)
    )
    expected_dataset_fingerprint = _fingerprint(
        "rag-tiered-regression-dataset-input/v2",
        [{"caseId": case_id, "inputFingerprint": fingerprints[case_id]} for case_id in case_ids],
    )
    if case_set_fingerprint != expected_case_set_fingerprint:
        raise EvidenceError("BASELINE_CASE_SET_FINGERPRINT_INVALID")
    if dataset_input_fingerprint != expected_dataset_fingerprint:
        raise EvidenceError("BASELINE_DATASET_FINGERPRINT_INVALID")
    _validate_v2_overall_consistency(
        overall, list(cases.values()), "BASELINE_OVERALL_METRIC_INCONSISTENT"
    )
    return {
        "baselineId": baseline_id,
        "sourceRevision": source_revision,
        "evaluationType": evaluation_type,
        "datasetVersion": dataset_version,
        "configurationFingerprint": configuration_fingerprint,
        "caseSetFingerprint": case_set_fingerprint,
        "datasetInputFingerprint": dataset_input_fingerprint,
        "overall": overall,
        "cases": cases,
        "caseIds": case_ids,
        "caseInputFingerprints": fingerprints,
        "criticalCaseIds": derive_critical_case_ids(cases),
    }


def _validate_policy_header(payload: Any) -> dict[str, Any]:
    version = _schema_version(payload, "policy", evidence=False)
    assert isinstance(payload, dict)
    policy_id = _safe_control_id(payload.get("policyId"))
    tier = _safe_control_id(payload.get("tier"))
    configured = payload.get("configured")
    if not isinstance(configured, bool):
        raise OperationError("policy configured flag invalid")
    scope = _safe_control_id(payload.get("policyScope"))
    owner_required = payload.get("ownerDecisionRequired")
    if not isinstance(owner_required, bool):
        raise OperationError("policy owner boundary invalid")
    deferred = payload.get("deferredOwnerFields", [])
    if not isinstance(deferred, list) or any(
        not isinstance(item, str) or SAFE_ID.fullmatch(item) is None for item in deferred
    ):
        raise OperationError("policy deferred fields invalid")
    if not configured and (not owner_required or not deferred):
        raise OperationError("unconfigured policy must name deferred Owner fields")
    return {
        "_schemaVersion": version,
        "policyId": policy_id,
        "tier": tier,
        "configured": configured,
        "policyScope": scope,
        "ownerDecisionRequired": owner_required,
        "deferredOwnerFields": sorted(set(deferred)),
    }


def _validate_configured_policy(payload: Mapping[str, Any], header: dict[str, Any]) -> dict[str, Any]:
    if header["ownerDecisionRequired"] or header["deferredOwnerFields"]:
        raise OperationError("configured engineering policy cannot retain Owner placeholders")
    baseline_id = _safe_control_id(payload.get("baselineId"))
    baseline_sha256 = _require_plain_hash(payload.get("baselineSha256"))
    evaluation_type = _safe_control_id(payload.get("evaluationType"))
    dataset_version = _safe_control_id(payload.get("datasetVersion"))
    configuration_fingerprint = payload.get("configurationFingerprint")
    case_set_fingerprint = payload.get("caseSetFingerprint")
    dataset_input_fingerprint = payload.get("datasetInputFingerprint")
    if not isinstance(configuration_fingerprint, str) or SHA256.fullmatch(configuration_fingerprint) is None:
        raise OperationError("policy configuration fingerprint invalid")
    if not isinstance(case_set_fingerprint, str) or SHA256.fullmatch(case_set_fingerprint) is None:
        raise OperationError("policy case-set fingerprint invalid")
    if not isinstance(dataset_input_fingerprint, str) or SHA256.fullmatch(dataset_input_fingerprint) is None:
        raise OperationError("policy dataset fingerprint invalid")
    frozen_ids = payload.get("frozenFixtureCaseIds")
    if not isinstance(frozen_ids, list) or not frozen_ids:
        raise OperationError("policy fixture case IDs invalid")
    normalized_ids = []
    for item in frozen_ids:
        normalized_ids.append(_safe_control_id(item))
    if len(set(normalized_ids)) != len(normalized_ids):
        raise OperationError("policy fixture case IDs invalid")
    rules = payload.get("comparisonRules")
    if not isinstance(rules, dict) or set(rules) != {"overall", "ANSWERABLE", "UNANSWERABLE"}:
        raise OperationError("policy comparison rules invalid")
    expected_fields = {
        "overall": OVERALL_METRIC_FIELDS,
        "ANSWERABLE": ANSWERABLE_METRIC_FIELDS,
        "UNANSWERABLE": UNANSWERABLE_METRIC_FIELDS,
    }
    normalized_rules: dict[str, dict[str, str]] = {}
    for scope, fields in expected_fields.items():
        scoped = rules.get(scope)
        if not isinstance(scoped, dict) or set(scoped) != fields:
            raise OperationError("policy comparison rules invalid")
        if any(rule not in SUPPORTED_RULES for rule in scoped.values()):
            raise OperationError("policy comparison rules invalid")
        normalized_rules[scope] = dict(scoped)
    return {
        **header,
        "baselineId": baseline_id,
        "baselineSha256": baseline_sha256,
        "evaluationType": evaluation_type,
        "datasetVersion": dataset_version,
        "configurationFingerprint": configuration_fingerprint,
        "caseSetFingerprint": case_set_fingerprint,
        "datasetInputFingerprint": dataset_input_fingerprint,
        "frozenFixtureCaseIds": tuple(sorted(normalized_ids)),
        "comparisonRules": normalized_rules,
    }


def _validate_configured_policy_v2(
    payload: Mapping[str, Any], header: dict[str, Any]
) -> dict[str, Any]:
    if header["ownerDecisionRequired"] or header["deferredOwnerFields"]:
        raise OperationError("configured business policy cannot retain Owner placeholders")
    expected_top_level = {
        "schema",
        "configured",
        "deferredOwnerFields",
        "ownerDecisionRequired",
        "policyId",
        "policyScope",
        "tier",
        "baselineId",
        "baselineSha256",
        "evaluationType",
        "datasetVersion",
        "configurationFingerprint",
        "caseSetFingerprint",
        "datasetInputFingerprint",
        "frozenCaseIds",
        "comparisonRules",
    }
    if set(payload) != expected_top_level:
        raise OperationError("v2 policy fields invalid")
    baseline_id = _safe_control_id(payload.get("baselineId"))
    baseline_sha256 = _require_plain_hash(payload.get("baselineSha256"))
    evaluation_type = _safe_control_id(payload.get("evaluationType"))
    dataset_version = _safe_control_id(payload.get("datasetVersion"))
    fingerprints: dict[str, str] = {}
    for name in (
        "configurationFingerprint",
        "caseSetFingerprint",
        "datasetInputFingerprint",
    ):
        value = payload.get(name)
        if not isinstance(value, str) or SHA256.fullmatch(value) is None:
            raise OperationError("v2 policy fingerprint invalid")
        fingerprints[name] = value
    frozen_ids = payload.get("frozenCaseIds")
    if not isinstance(frozen_ids, list) or not frozen_ids:
        raise OperationError("v2 policy case IDs invalid")
    normalized_ids = [_safe_control_id(item) for item in frozen_ids]
    if len(set(normalized_ids)) != len(normalized_ids):
        raise OperationError("v2 policy case IDs invalid")
    rules = payload.get("comparisonRules")
    if not isinstance(rules, dict) or set(rules) != {
        "businessOverall",
        "criticalCases",
    }:
        raise OperationError("v2 policy comparison rules invalid")
    normalized_rules: dict[str, dict[str, str]] = {}
    for scope in ("businessOverall", "criticalCases"):
        scoped = rules.get(scope)
        if not isinstance(scoped, dict) or set(scoped) != V2_METRIC_FIELDS:
            raise OperationError("v2 policy comparison rules invalid")
        if any(rule != "NOT_LOWER" for rule in scoped.values()):
            raise OperationError("v2 policy comparison rules invalid")
        normalized_rules[scope] = dict(scoped)
    return {
        **header,
        "baselineId": baseline_id,
        "baselineSha256": baseline_sha256,
        "evaluationType": evaluation_type,
        "datasetVersion": dataset_version,
        **fingerprints,
        "frozenFixtureCaseIds": tuple(sorted(normalized_ids)),
        "comparisonRules": normalized_rules,
    }


def _finding(code: str, **safe_details: Any) -> dict[str, Any]:
    return {"code": code, **safe_details}


def compare_identity(
    policy: Mapping[str, Any],
    baseline: Mapping[str, Any],
    candidate: CandidateIdentity,
    baseline_sha256: str,
) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    if policy["baselineSha256"] != baseline_sha256:
        findings.append(_finding("BASELINE_HASH_MISMATCH"))
    if policy["baselineId"] != baseline["baselineId"]:
        findings.append(_finding("BASELINE_ID_MISMATCH"))
    for code, key, candidate_value in (
        ("EVALUATION_TYPE_MISMATCH", "evaluationType", candidate.evaluation_type),
        ("DATASET_VERSION_MISMATCH", "datasetVersion", candidate.dataset_version),
        ("CONFIGURATION_FINGERPRINT_MISMATCH", "configurationFingerprint", candidate.configuration_fingerprint),
        ("CASE_SET_FINGERPRINT_MISMATCH", "caseSetFingerprint", candidate.case_set_fingerprint),
        ("DATASET_INPUT_FINGERPRINT_MISMATCH", "datasetInputFingerprint", candidate.dataset_input_fingerprint),
    ):
        if policy[key] != baseline[key] or baseline[key] != candidate_value:
            findings.append(_finding(code))
    if candidate.configuration_has_extra_fields:
        findings.append(_finding("CONFIGURATION_FIELD_SET_MISMATCH"))
    baseline_ids = tuple(baseline["caseIds"])
    if tuple(policy["frozenFixtureCaseIds"]) != baseline_ids:
        unknown = sorted(set(policy["frozenFixtureCaseIds"]) - set(baseline_ids))
        if unknown:
            findings.extend(
                _finding("POLICY_FIXTURE_CASE_UNKNOWN", caseId=case_id) for case_id in unknown
            )
        else:
            findings.append(_finding("POLICY_FIXTURE_CASE_SET_MISMATCH"))
    if candidate.case_ids != baseline_ids:
        missing = sorted(set(baseline_ids) - set(candidate.case_ids))
        extra = sorted(set(candidate.case_ids) - set(baseline_ids))
        findings.append(
            _finding(
                "CANDIDATE_CASE_SET_MISMATCH",
                missingCaseIds=missing,
                extraCaseCount=len(extra),
            )
        )
        return findings
    for case_id in baseline_ids:
        if baseline["cases"][case_id]["answerability"] != candidate.answerability[case_id]:
            findings.append(_finding("CASE_ANSWERABILITY_MISMATCH", caseId=case_id))
        if baseline["caseInputFingerprints"][case_id] != candidate.case_input_fingerprints[case_id]:
            findings.append(_finding("CASE_INPUT_FINGERPRINT_MISMATCH", caseId=case_id))
    return findings


def _comparison(
    *,
    scope: str,
    metric: str,
    rule: str,
    baseline: Any,
    candidate: Any,
    case_id: str | None = None,
) -> dict[str, Any] | None:
    if rule == "FALSE_OR_EQUAL":
        if not isinstance(baseline, bool) or not isinstance(candidate, bool):
            raise EvidenceError("BOOLEAN_COMPARISON_EVIDENCE_INVALID")
        delta: int | float = int(candidate) - int(baseline)
        regression = baseline is False and candidate is True
        improvement = baseline is True and candidate is False
    else:
        if not _is_number(baseline) or not _is_number(candidate):
            raise EvidenceError("NUMERIC_COMPARISON_EVIDENCE_INVALID")
        delta = round(float(candidate) - float(baseline), 12)
        regression = candidate < baseline if rule == "NOT_LOWER" else candidate > baseline
        improvement = candidate > baseline if rule == "NOT_LOWER" else candidate < baseline
    if not regression and not improvement:
        return None
    result: dict[str, Any] = {
        "scope": scope,
        "metric": metric,
        "rule": rule,
        "baseline": baseline,
        "candidate": candidate,
        "delta": delta,
        "classification": "REGRESSION" if regression else "IMPROVEMENT",
    }
    if case_id is not None:
        result["caseId"] = case_id
    return result


def compare_metrics(
    policy: Mapping[str, Any],
    baseline: Mapping[str, Any],
    candidate_payload: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], int]:
    differences: list[dict[str, Any]] = []
    checked = 0
    candidate_overall = candidate_payload["overall"]
    for metric in sorted(policy["comparisonRules"]["overall"]):
        checked += 1
        difference = _comparison(
            scope="overall",
            metric=metric,
            rule=policy["comparisonRules"]["overall"][metric],
            baseline=baseline["overall"][metric],
            candidate=candidate_overall[metric],
        )
        if difference is not None:
            differences.append(difference)
    candidate_cases = {case["caseId"]: case for case in candidate_payload["cases"]}
    for case_id in baseline["caseIds"]:
        answerability = baseline["cases"][case_id]["answerability"]
        rules = policy["comparisonRules"][answerability]
        for metric in sorted(rules):
            checked += 1
            difference = _comparison(
                scope="case",
                case_id=case_id,
                metric=metric,
                rule=rules[metric],
                baseline=baseline["cases"][case_id]["metrics"][metric],
                candidate=candidate_cases[case_id]["metrics"][metric],
            )
            if difference is not None:
                differences.append(difference)
    differences.sort(key=lambda item: (item["scope"], item.get("caseId", ""), item["metric"]))
    return differences, checked


def compare_metrics_v2(
    policy: Mapping[str, Any],
    baseline: Mapping[str, Any],
    candidate_payload: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], int]:
    differences: list[dict[str, Any]] = []
    checked = 0
    candidate_overall = candidate_payload["overall"]
    for metric in sorted(policy["comparisonRules"]["businessOverall"]):
        checked += 1
        difference = _comparison(
            scope="businessOverall",
            metric=metric,
            rule=policy["comparisonRules"]["businessOverall"][metric],
            baseline=baseline["overall"][metric],
            candidate=candidate_overall[metric],
        )
        if difference is not None:
            differences.append(difference)
    candidate_cases = {case["caseId"]: case for case in candidate_payload["cases"]}
    for case_id in baseline["criticalCaseIds"]:
        for metric in sorted(policy["comparisonRules"]["criticalCases"]):
            checked += 1
            difference = _comparison(
                scope="criticalCase",
                case_id=case_id,
                metric=metric,
                rule=policy["comparisonRules"]["criticalCases"][metric],
                baseline=baseline["cases"][case_id]["metrics"][metric],
                candidate=candidate_cases[case_id]["metrics"][metric],
            )
            if difference is not None:
                differences.append(difference)
    differences.sort(key=lambda item: (item["scope"], item.get("caseId", ""), item["metric"]))
    return differences, checked


def build_result(
    *,
    exit_code: int,
    policy: Mapping[str, Any],
    input_hashes: Mapping[str, str | None],
    identity: Mapping[str, Any] | None = None,
    findings: Sequence[Mapping[str, Any]] = (),
    differences: Sequence[Mapping[str, Any]] = (),
    checked_metric_count: int = 0,
) -> dict[str, Any]:
    regression_count = sum(item.get("classification") == "REGRESSION" for item in differences)
    improvement_count = sum(item.get("classification") == "IMPROVEMENT" for item in differences)
    return {
        "schema": (
            RESULT_SCHEMA_V2
            if policy.get("_schemaVersion") == "v2"
            else RESULT_SCHEMA
        ),
        "aggregateOnly": True,
        "rawSamplesIncluded": False,
        "verdict": VERDICTS[exit_code],
        "exitCode": exit_code,
        "claimBoundary": "ENGINEERING_REGRESSION_PLUMBING_ONLY",
        "policy": {
            "policyId": policy["policyId"],
            "tier": policy["tier"],
            "policyScope": policy["policyScope"],
            "configured": policy["configured"],
        },
        "ownerDecisionDeferred": list(policy.get("deferredOwnerFields", [])),
        "inputs": dict(input_hashes),
        "identity": dict(identity or {}),
        "comparison": {
            "checkedMetricCount": checked_metric_count,
            "differenceCount": len(differences),
            "regressionCount": regression_count,
            "improvementCount": improvement_count,
            "differences": list(differences),
        },
        "findings": list(findings),
    }


def evaluate_gate(config: Config) -> GateOutcome:
    policy_payload, policy_sha256 = _read_json(config.policy, evidence=False)
    policy_header = _validate_policy_header(policy_payload)
    schema_version = policy_header["_schemaVersion"]
    input_hashes: dict[str, str | None] = {
        "policySha256": policy_sha256,
        "baselineSha256": None,
        "candidateSha256": None,
    }
    if not policy_header["configured"]:
        report = build_result(
            exit_code=OWNER_DECISION_DEFERRED,
            policy=policy_header,
            input_hashes=input_hashes,
            findings=[_finding("OWNER_POLICY_NOT_CONFIGURED")],
        )
        return GateOutcome(OWNER_DECISION_DEFERRED, report)

    policy = (
        _validate_configured_policy_v2(policy_payload, policy_header)
        if schema_version == "v2"
        else _validate_configured_policy(policy_payload, policy_header)
    )
    try:
        baseline_payload, baseline_sha256 = _read_json(config.baseline, evidence=True)
        input_hashes["baselineSha256"] = baseline_sha256
        if _schema_version(baseline_payload, "baseline", evidence=True) != schema_version:
            raise EvidenceError("BASELINE_SCHEMA_VERSION_MISMATCH")
        baseline = (
            _validate_baseline_v2(baseline_payload)
            if schema_version == "v2"
            else _validate_baseline(baseline_payload)
        )
    except EvidenceError as exc:
        report = build_result(
            exit_code=INFRA_FAILURE,
            policy=policy,
            input_hashes=input_hashes,
            findings=[_finding("BASELINE_EVIDENCE_INVALID", reason=exc.code)],
        )
        return GateOutcome(INFRA_FAILURE, report)

    try:
        candidate_payload, candidate_sha256 = _read_json(config.candidate, evidence=True)
        input_hashes["candidateSha256"] = candidate_sha256
        if _schema_version(candidate_payload, "candidate", evidence=True) != schema_version:
            raise EvidenceError("CANDIDATE_SCHEMA_VERSION_MISMATCH")
        candidate_identity = (
            inspect_candidate_identity_v2(candidate_payload)
            if schema_version == "v2"
            else inspect_candidate_identity(candidate_payload)
        )
    except EvidenceError as exc:
        report = build_result(
            exit_code=INFRA_FAILURE,
            policy=policy,
            input_hashes=input_hashes,
            identity={
                "baselineId": baseline["baselineId"],
                "evaluationType": baseline["evaluationType"],
                "datasetVersion": baseline["datasetVersion"],
            },
            findings=[_finding("CANDIDATE_EVIDENCE_INVALID", reason=exc.code)],
        )
        return GateOutcome(INFRA_FAILURE, report)

    identity = {
        "baselineId": baseline["baselineId"],
        "evaluationType": baseline["evaluationType"],
        "datasetVersion": baseline["datasetVersion"],
        "configurationFingerprint": candidate_identity.configuration_fingerprint,
        "caseSetFingerprint": candidate_identity.case_set_fingerprint,
        "datasetInputFingerprint": candidate_identity.dataset_input_fingerprint,
        "caseCount": len(candidate_identity.case_ids),
    }
    if schema_version == "v2":
        identity["criticalCaseIds"] = list(baseline["criticalCaseIds"])
    try:
        if schema_version == "v2":
            validate_candidate_evidence_v2(candidate_payload, candidate_identity)
        else:
            validate_candidate_evidence(candidate_payload, candidate_identity)
    except EvidenceError as exc:
        report = build_result(
            exit_code=INFRA_FAILURE,
            policy=policy,
            input_hashes=input_hashes,
            identity=identity,
            findings=[_finding("CANDIDATE_EVIDENCE_INVALID", reason=exc.code)],
        )
        return GateOutcome(INFRA_FAILURE, report)

    drift_findings = compare_identity(policy, baseline, candidate_identity, baseline_sha256)
    if drift_findings:
        report = build_result(
            exit_code=DATA_MODEL_DRIFT,
            policy=policy,
            input_hashes=input_hashes,
            identity=identity,
            findings=drift_findings,
        )
        return GateOutcome(DATA_MODEL_DRIFT, report)

    try:
        if schema_version == "v2":
            differences, checked = compare_metrics_v2(policy, baseline, candidate_payload)
        else:
            differences, checked = compare_metrics(policy, baseline, candidate_payload)
    except EvidenceError as exc:
        report = build_result(
            exit_code=INFRA_FAILURE,
            policy=policy,
            input_hashes=input_hashes,
            identity=identity,
            findings=[_finding("CANDIDATE_EVIDENCE_INVALID", reason=exc.code)],
        )
        return GateOutcome(INFRA_FAILURE, report)
    exit_code = CODE_REGRESSION if any(
        item["classification"] == "REGRESSION" for item in differences
    ) else PASS
    report = build_result(
        exit_code=exit_code,
        policy=policy,
        input_hashes=input_hashes,
        identity=identity,
        differences=differences,
        checked_metric_count=checked,
    )
    return GateOutcome(exit_code, report)


def write_result_exclusive(path: Path, report: Mapping[str, Any]) -> None:
    if not path.parent.is_dir() or path.exists():
        raise OperationError("output must be a new file in an existing directory")
    try:
        payload = json.dumps(
            report,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        ) + "\n"
    except (TypeError, ValueError) as exc:
        raise OperationError("result serialization failed") from exc
    temporary: str | None = None
    try:
        descriptor, temporary = tempfile.mkstemp(
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
        )
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)
    except (FileExistsError, OSError) as exc:
        raise OperationError("atomic exclusive result publication failed") from exc
    finally:
        if temporary is not None:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
            except OSError as exc:
                raise OperationError("temporary result cleanup failed") from exc


def parse_config(argv: Sequence[str] | None = None) -> Config:
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--policy", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    return Config(
        baseline=Path(args.baseline).resolve(),
        candidate=Path(args.candidate).resolve(),
        policy=Path(args.policy).resolve(),
        output=Path(args.output).resolve(),
    )


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config = parse_config(argv)
        if not config.output.parent.is_dir() or config.output.exists():
            raise OperationError("output boundary invalid")
        outcome = evaluate_gate(config)
        write_result_exclusive(config.output, outcome.report)
        print(f"{VERDICTS[outcome.exit_code]}: exit {outcome.exit_code}")
        return outcome.exit_code
    except OperationError:
        print("OPERATION_ERROR: exit 1", file=sys.stderr)
        return OPERATION_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
