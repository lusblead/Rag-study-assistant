"""Fail-closed, standard-library workflow for local RAG performance evidence."""

from __future__ import annotations

import csv
import hashlib
import ipaddress
import json
import math
import os
import platform
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence


EXPECTED_JMETER_VERSION = "5.6.3"
EXPECTED_REPORT_SCHEMA_SHA256 = (
    "70a32c459b03a76f619a03ac29c8c8d25aaf9984a21fda4e1bc86f9d37ca6169"
)
PRIMARY_LABELS = {
    "L1": "L1-retrieval",
    "L2": "L2-ingest-terminal",
    "L3": "L3-chat",
}
VALID_RUN_STATUSES = {"NOT_RUN", "COMPLETED", "STOPPED", "FAILED"}
VALID_EVIDENCE_LEVELS = {"FIXTURE_STATIC", "RUNTIME", "PRODUCTION"}
VALID_RUNTIME_EVIDENCE_STATUSES = {"NOT_RUN", "VALID", "INVALID"}
VALID_THRESHOLD_STATUSES = {
    "NOT_EVALUATED",
    "WITHIN_PROFILE",
    "STOPPED_BY_THRESHOLD",
    "INVALID",
}
THRESHOLD_STOP_REASON_KINDS = {
    "CPU",
    "ERROR_RATE",
    "HTTP_429",
    "MEMORY",
    "P99_CONSECUTIVE_WINDOWS",
}
FAILURE_STOP_REASON_KINDS = {
    "RUNTIME_EVIDENCE",
    "RUNTIME_IDENTITY",
    "SOURCE_DRIFT",
}
FORBIDDEN_PROFILE_KEYS = {
    "token",
    "query",
    "question",
    "body",
    "courseid",
    "documentid",
    "jobid",
    "sessionid",
}


class WorkflowError(RuntimeError):
    """A safe operator-facing workflow failure without sensitive context."""


class RuntimeEvidenceError(WorkflowError):
    """Runtime evidence is absent or unusable and must not be fabricated."""


@dataclass(frozen=True)
class Sample:
    timestamp_ms: int
    elapsed_ms: int
    label: str
    response_code: str
    success: bool
    thread_name: str = "thread-1"


@dataclass
class ResourceStopState:
    cpu_above_started_seconds: float | None = None
    last_sampled_seconds: float | None = None


@dataclass(frozen=True)
class InvocationResult:
    stop_reason: dict[str, str] | None
    duration_seconds: float


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def snapshot_file_exclusive(source: Path, destination: Path) -> str:
    try:
        value = source.read_bytes()
        with destination.open("xb") as handle:
            handle.write(value)
    except OSError as exc:
        raise WorkflowError(f"could not snapshot {source.name}") from exc
    return sha256_bytes(value)


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise WorkflowError(f"invalid JSON file: {path.name}") from exc
    if not isinstance(value, dict):
        raise WorkflowError(f"JSON root must be an object: {path.name}")
    return value


def validate_report_schema_contract(path: Path) -> str:
    schema = load_json_object(path)
    observed_hash = sha256_file(path)
    if observed_hash != EXPECTED_REPORT_SCHEMA_SHA256:
        raise WorkflowError("report schema does not match the pinned v1 contract")
    if (
        schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
        or schema.get("$id")
        != "https://rag-study-assistant.local/schemas/performance-report-v1.schema.json"
        or schema.get("additionalProperties") is not False
    ):
        raise WorkflowError("report schema metadata or closure is invalid")
    return observed_hash


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _walk_keys(value: Any) -> Iterable[str]:
    if isinstance(value, dict):
        for key, nested in value.items():
            yield str(key)
            yield from _walk_keys(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _walk_keys(nested)


def external_policy_errors(external: Any) -> list[str]:
    if not isinstance(external, dict):
        return ["external must be an object"]
    mode = external.get("mode")
    budget = external.get("costBudgetUsd")
    pricing = external.get("pricingVersion")
    zero_call_path = external.get("zeroExternalCallMetricPath")
    errors: list[str] = []
    if mode not in {"disabled", "provider-budgeted"}:
        errors.append("external.mode must be disabled or provider-budgeted")
    if not _is_number(budget) or budget < 0:
        errors.append("external.costBudgetUsd must be a non-negative number")
    if mode == "disabled":
        if budget != 0:
            errors.append("disabled external mode requires zero cost budget")
        if pricing is not None:
            errors.append("disabled external mode requires null pricingVersion")
    if mode == "provider-budgeted":
        if not _is_number(budget) or budget <= 0:
            errors.append("provider-budgeted mode requires a positive cost budget")
        if not isinstance(pricing, str) or not pricing.strip():
            errors.append("provider-budgeted mode requires a pricingVersion")
    if zero_call_path is not None:
        if not isinstance(zero_call_path, str) or not zero_call_path.startswith("/"):
            errors.append("zeroExternalCallMetricPath must be null or a local absolute path")
        elif urllib.parse.urlsplit(zero_call_path).scheme or urllib.parse.urlsplit(zero_call_path).netloc:
            errors.append("zeroExternalCallMetricPath cannot contain another origin")
    return errors


def profile_errors(profile: Any) -> list[str]:
    if not isinstance(profile, dict):
        return ["profile must be an object"]
    errors: list[str] = []
    if profile.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if not isinstance(profile.get("name"), str) or not profile.get("name", "").strip():
        errors.append("name must be a non-empty string")
    base_url = profile.get("baseUrl")
    if not isinstance(base_url, str) or not base_url.strip():
        errors.append("baseUrl must be a non-empty string")
    else:
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            errors.append("baseUrl must be an absolute HTTP(S) URL")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            errors.append("baseUrl cannot contain credentials, query, or fragment")
        if parsed.path not in {"", "/"}:
            errors.append("baseUrl must be an origin without a path")

    layers = profile.get("layers")
    if not isinstance(layers, dict):
        errors.append("layers must be an object")
    else:
        for layer in ("L1", "L2", "L3", "L4"):
            if not isinstance(layers.get(layer), bool):
                errors.append(f"layers.{layer} must be boolean")
        if layers.get("L4") is not False:
            errors.append("L4 is DEFERRED and must be false")
        if not any(layers.get(layer) is True for layer in ("L1", "L2", "L3")):
            errors.append("at least one of L1-L3 must be enabled")

    workload = profile.get("workload")
    expected_workload_fields = {
        "fixtureFingerprintSha256",
        "runtimeBuildFingerprintSha256",
        "requestModel",
        "corpusDocumentCount",
        "corpusChunkCount",
        "ingestFileSizeBytes",
        "ingestPageCount",
    }
    if not isinstance(workload, dict):
        errors.append("workload must be an object")
    else:
        if set(workload) != expected_workload_fields:
            errors.append("workload fields do not match the versioned contract")
        for key in ("fixtureFingerprintSha256", "runtimeBuildFingerprintSha256"):
            value = workload.get(key)
            if value is not None and (
                not isinstance(value, str)
                or re.fullmatch(r"[0-9a-f]{64}", value) is None
            ):
                errors.append(f"workload.{key} must be null or a lowercase SHA-256")
        request_model = workload.get("requestModel")
        if (
            not isinstance(request_model, str)
            or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,79}", request_model) is None
        ):
            errors.append("workload.requestModel must be a stable low-cardinality identifier")
        for key in (
            "corpusDocumentCount",
            "corpusChunkCount",
            "ingestFileSizeBytes",
            "ingestPageCount",
        ):
            value = workload.get(key)
            if value is not None and (
                not isinstance(value, int)
                or isinstance(value, bool)
                or value <= 0
            ):
                errors.append(f"workload.{key} must be null or a positive integer")

    load = profile.get("load")
    if not isinstance(load, dict):
        errors.append("load must be an object")
    else:
        concurrency = load.get("concurrency")
        if (
            not isinstance(concurrency, list)
            or not concurrency
            or any(not isinstance(item, int) or isinstance(item, bool) or item <= 0 for item in concurrency)
            or concurrency != sorted(set(concurrency))
        ):
            errors.append("load.concurrency must be a sorted unique list of positive integers")
        for key in (
            "warmupSeconds",
            "steadyStateSeconds",
            "analysisWindowSeconds",
            "ingestPollIntervalMs",
            "ingestPollTimeoutSeconds",
        ):
            value = load.get(key)
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                errors.append(f"load.{key} must be a positive integer")
        ramp = load.get("rampSeconds")
        if not isinstance(ramp, int) or isinstance(ramp, bool) or ramp < 0:
            errors.append("load.rampSeconds must be a non-negative integer")

    stops = profile.get("stopConditions")
    if not isinstance(stops, dict):
        errors.append("stopConditions must be an object")
    else:
        bounded = (
            ("errorRateGreaterThan", 0.0, 1.0),
            ("cpuGreaterThan", 0.0, 1.0),
            ("memoryGreaterThan", 0.0, 1.0),
        )
        for key, lower, upper in bounded:
            value = stops.get(key)
            if not _is_number(value) or not lower <= value <= upper:
                errors.append(f"stopConditions.{key} must be between 0 and 1")
        multiplier = stops.get("p99MultiplierOverLowestConcurrency")
        if not _is_number(multiplier) or multiplier <= 1:
            errors.append("P99 multiplier must be greater than 1")
        windows = stops.get("p99ConsecutiveWindows")
        if not isinstance(windows, int) or isinstance(windows, bool) or windows < 1:
            errors.append("P99 consecutive windows must be a positive integer")
        cpu_seconds = stops.get("cpuConsecutiveSeconds")
        if not isinstance(cpu_seconds, int) or isinstance(cpu_seconds, bool) or cpu_seconds <= 0:
            errors.append("CPU consecutive seconds must be a positive integer")
        if stops.get("stopOnAny429") is not True:
            errors.append("stopOnAny429 must be true for fail-closed execution")
        if isinstance(load, dict):
            steady = load.get("steadyStateSeconds")
            window = load.get("analysisWindowSeconds")
            if (
                isinstance(steady, int)
                and not isinstance(steady, bool)
                and isinstance(window, int)
                and not isinstance(window, bool)
                and isinstance(windows, int)
                and not isinstance(windows, bool)
                and steady < window * windows
            ):
                errors.append(
                    "steadyStateSeconds must contain all required complete P99 windows"
                )

    execution = profile.get("execution")
    if not isinstance(execution, dict):
        errors.append("execution must be an object")
    else:
        for key in ("enabled", "disposableStateConfirmed"):
            if not isinstance(execution.get(key), bool):
                errors.append(f"execution.{key} must be boolean")

    errors.extend(external_policy_errors(profile.get("external")))

    actuator = profile.get("actuator")
    if not isinstance(actuator, dict):
        errors.append("actuator must be an object")
    else:
        actuator_base_url = actuator.get("baseUrl")
        if not isinstance(actuator_base_url, str) or not is_loopback_base_url(actuator_base_url):
            errors.append("actuator.baseUrl must be a loopback HTTP(S) origin")
        for key in (
            "healthPath",
            "infoPath",
            "cpuMetricPath",
            "memoryUsedMetricPath",
            "memoryMaxMetricPath",
        ):
            value = actuator.get(key)
            if not isinstance(value, str) or not value.startswith("/"):
                errors.append(f"actuator.{key} must be a local absolute path")
            elif urllib.parse.urlsplit(value).scheme or urllib.parse.urlsplit(value).netloc:
                errors.append(f"actuator.{key} cannot contain another origin")
        interval = actuator.get("sampleIntervalSeconds")
        if not _is_number(interval) or interval <= 0:
            errors.append("actuator.sampleIntervalSeconds must be positive")

    forbidden = sorted(
        key for key in _walk_keys(profile) if key.lower().replace("_", "") in FORBIDDEN_PROFILE_KEYS
    )
    if forbidden:
        errors.append("profile must not contain payload, token, query, or business-ID fields")
    return errors


def validate_profile(profile: Any) -> None:
    errors = profile_errors(profile)
    if errors:
        raise WorkflowError("profile validation failed: " + "; ".join(errors))


def is_loopback_base_url(base_url: str) -> bool:
    try:
        parsed = urllib.parse.urlsplit(base_url)
        host = parsed.hostname
        if parsed.scheme not in {"http", "https"} or host is None:
            return False
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            return False
        if parsed.path not in {"", "/"}:
            return False
        if host.lower() == "localhost":
            return True
        return ipaddress.ip_address(host).is_loopback
    except (ValueError, TypeError):
        return False


def execution_gate_errors(profile: Mapping[str, Any], explicit_execute: bool) -> list[str]:
    errors = profile_errors(profile)
    if not explicit_execute:
        errors.append("explicit --execute is required")
    execution = profile.get("execution") if isinstance(profile, dict) else None
    if not isinstance(execution, dict) or execution.get("enabled") is not True:
        errors.append("profile execution.enabled must be true")
    if not isinstance(execution, dict) or execution.get("disposableStateConfirmed") is not True:
        errors.append("profile execution.disposableStateConfirmed must be true")
    if not is_loopback_base_url(str(profile.get("baseUrl", ""))):
        errors.append("execution baseUrl must be loopback")
    layers = profile.get("layers") if isinstance(profile, dict) else None
    if not isinstance(layers, dict) or layers.get("L4") is not False:
        errors.append("L4 execution is forbidden")
    workload = profile.get("workload") if isinstance(profile, dict) else None
    if not isinstance(workload, dict):
        errors.append("workload execution metadata is required")
    else:
        for key in ("fixtureFingerprintSha256", "runtimeBuildFingerprintSha256"):
            if (
                not isinstance(workload.get(key), str)
                or re.fullmatch(r"[0-9a-f]{64}", workload[key]) is None
            ):
                errors.append(f"workload.{key} is required for execution")
        if isinstance(layers, dict) and (layers.get("L1") or layers.get("L3")):
            for key in ("corpusDocumentCount", "corpusChunkCount"):
                if not isinstance(workload.get(key), int) or isinstance(workload.get(key), bool) or workload[key] <= 0:
                    errors.append(f"workload.{key} is required for L1/L3 execution")
        if isinstance(layers, dict) and layers.get("L2"):
            for key in ("ingestFileSizeBytes", "ingestPageCount"):
                if not isinstance(workload.get(key), int) or isinstance(workload.get(key), bool) or workload[key] <= 0:
                    errors.append(f"workload.{key} is required for L2 execution")
    external = profile.get("external") if isinstance(profile, dict) else None
    if isinstance(layers, dict) and any(layers.get(layer) is True for layer in ("L1", "L2", "L3")) and isinstance(external, dict):
        if external.get("mode") == "disabled" and not external.get("zeroExternalCallMetricPath"):
            errors.append("execution is NOT_READY without a verifiable zero-external-call metric")
        if external.get("mode") == "provider-budgeted":
            errors.append("provider-budgeted execution is unsupported while provider usage and cost are UNKNOWN")
    return list(dict.fromkeys(errors))


def validate_execution_gate(profile: Mapping[str, Any], explicit_execute: bool) -> None:
    errors = execution_gate_errors(profile, explicit_execute)
    if errors:
        raise WorkflowError("execution gate rejected: " + "; ".join(errors))


def _load_env_json(environ: Mapping[str, str], name: str) -> dict[str, Any] | None:
    raw = environ.get(name)
    if raw is None or not raw.strip():
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def runtime_environment_errors(profile: Mapping[str, Any], environ: Mapping[str, str]) -> list[str]:
    layers = profile["layers"]
    errors: list[str] = []
    if layers["L1"]:
        if not environ.get("RAG_PERFORMANCE_TOKEN", "").strip():
            errors.append("RAG_PERFORMANCE_TOKEN is required for L1")
        payload = _load_env_json(environ, "RAG_PERFORMANCE_L1_BODY")
        if payload is None:
            errors.append("RAG_PERFORMANCE_L1_BODY must be a JSON object")
        else:
            course_id = payload.get("courseId")
            top_k = payload.get("topK")
            query = payload.get("query")
            if not isinstance(course_id, int) or isinstance(course_id, bool) or course_id <= 0:
                errors.append("L1 courseId must be a positive integer")
            if not isinstance(top_k, int) or isinstance(top_k, bool) or not 1 <= top_k <= 100:
                errors.append("L1 topK must be between 1 and 100")
            if not isinstance(query, str) or not query.strip() or len(query) > 2048:
                errors.append("L1 query must be non-blank and at most 2048 characters")
    if layers["L2"]:
        pool = parse_document_id_pool(environ)
        if pool is None:
            errors.append("RAG_PERFORMANCE_DOCUMENT_ID_POOL must be a JSON array of unique positive integers")
        else:
            load = profile["load"]
            minimum = load["concurrency"][0] + sum(load["concurrency"])
            if len(pool) < minimum:
                errors.append("RAG_PERFORMANCE_DOCUMENT_ID_POOL is too small for one unique ID per L2 thread/invocation")
    if layers["L3"]:
        payload = _load_env_json(environ, "RAG_PERFORMANCE_L3_BODY")
        if payload is None:
            errors.append("RAG_PERFORMANCE_L3_BODY must be a JSON object")
        else:
            course_id = payload.get("courseId")
            question = payload.get("question")
            if not isinstance(course_id, int) or isinstance(course_id, bool) or course_id <= 0:
                errors.append("L3 courseId must be a positive integer")
            if not isinstance(question, str) or not question.strip():
                errors.append("L3 question must be non-blank")
            if payload.get("sessionId") is not None:
                errors.append("L3 sessionId must be absent or null so each request creates a fresh session")
    return errors


def validate_runtime_environment(profile: Mapping[str, Any], environ: Mapping[str, str]) -> None:
    errors = runtime_environment_errors(profile, environ)
    if errors:
        raise WorkflowError("runtime environment gate rejected: " + "; ".join(errors))


def runtime_fixture_fingerprint(
    profile: Mapping[str, Any],
    environ: Mapping[str, str],
) -> str:
    """Bind the authorized low-cardinality scale metadata to in-memory payloads.

    Raw payloads and business identifiers are never returned or persisted.
    """
    errors = runtime_environment_errors(profile, environ)
    if errors:
        raise WorkflowError("runtime environment gate rejected: " + "; ".join(errors))
    layers = profile["layers"]
    workload = profile["workload"]
    fingerprint_input: dict[str, Any] = {
        "schemaVersion": 1,
        "layers": {layer: bool(layers[layer]) for layer in ("L1", "L2", "L3")},
        "requestModel": workload["requestModel"],
        "corpusDocumentCount": workload["corpusDocumentCount"],
        "corpusChunkCount": workload["corpusChunkCount"],
        "ingestFileSizeBytes": workload["ingestFileSizeBytes"],
        "ingestPageCount": workload["ingestPageCount"],
    }
    if layers["L1"]:
        fingerprint_input["l1Payload"] = _load_env_json(
            environ, "RAG_PERFORMANCE_L1_BODY"
        )
    if layers["L2"]:
        fingerprint_input["l2DocumentIdPool"] = parse_document_id_pool(environ)
    if layers["L3"]:
        fingerprint_input["l3Payload"] = _load_env_json(
            environ, "RAG_PERFORMANCE_L3_BODY"
        )
    canonical = json.dumps(
        fingerprint_input,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return sha256_bytes(canonical)


def verify_runtime_fixture_fingerprint(
    profile: Mapping[str, Any],
    environ: Mapping[str, str],
) -> str:
    observed = runtime_fixture_fingerprint(profile, environ)
    expected = profile["workload"]["fixtureFingerprintSha256"]
    if not secrets.compare_digest(observed, expected):
        raise WorkflowError(
            "runtime workload does not match workload.fixtureFingerprintSha256"
        )
    return observed


def parse_document_id_pool(environ: Mapping[str, str]) -> list[int] | None:
    raw = environ.get("RAG_PERFORMANCE_DOCUMENT_ID_POOL")
    if raw is None or not raw.strip():
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(value, list) or not value:
        return None
    if any(not isinstance(item, int) or isinstance(item, bool) or item <= 0 for item in value):
        return None
    if len(set(value)) != len(value):
        return None
    return value


def partition_unique_document_ids(document_ids: Sequence[int], weights: Sequence[int]) -> list[list[int]]:
    if not document_ids or not weights or any(weight <= 0 for weight in weights):
        raise WorkflowError("L2 document pool partition inputs are invalid")
    if len(document_ids) < len(weights):
        raise WorkflowError("L2 document pool cannot cover every invocation")
    total_weight = sum(weights)
    sizes = [max(1, len(document_ids) * weight // total_weight) for weight in weights]
    while sum(sizes) > len(document_ids):
        index = max(range(len(sizes)), key=lambda item: sizes[item])
        if sizes[index] <= 1:
            raise WorkflowError("L2 document pool partition is too small")
        sizes[index] -= 1
    remainder = len(document_ids) - sum(sizes)
    order = sorted(range(len(weights)), key=lambda item: weights[item], reverse=True)
    for offset in range(remainder):
        sizes[order[offset % len(order)]] += 1
    partitions: list[list[int]] = []
    cursor = 0
    for size in sizes:
        partitions.append(list(document_ids[cursor : cursor + size]))
        cursor += size
    return partitions


def validate_jmx(path: Path) -> dict[str, Any]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise WorkflowError("JMX XML validation failed") from exc
    errors: list[str] = []
    if root.tag != "jmeterTestPlan" or root.attrib.get("jmeter") != EXPECTED_JMETER_VERSION:
        errors.append("JMX must target JMeter 5.6.3")
    sampler_nodes = list(root.iter("HTTPSamplerProxy"))
    sampler_names = [item.attrib.get("testname", "") for item in sampler_nodes]
    samplers: dict[str, ET.Element] = {
        item.attrib.get("testname", ""): item for item in sampler_nodes
    }
    expected = {
        "L1-retrieval": ("POST", "/internal/performance/retrieval"),
        "L2-ingest-submit": ("POST", "/api/documents/"),
        "L2-ingest-poll": ("GET", "/api/ingestion/jobs/"),
        "L3-chat": ("POST", "/api/agent/chat"),
    }
    if (
        len(sampler_nodes) != len(expected)
        or len(set(sampler_names)) != len(sampler_names)
        or set(samplers) != set(expected)
    ):
        errors.append("JMX HTTP samplers must match the approved L1-L3 set exactly")
    for name, (method, path_fragment) in expected.items():
        sampler = samplers.get(name)
        if sampler is None:
            errors.append(f"missing sampler {name}")
            continue
        if sampler.attrib.get("enabled") != "true":
            errors.append(f"{name} must be enabled")
        values = {child.attrib.get("name"): child.text or "" for child in sampler.findall("stringProp")}
        boolean_values = {
            child.attrib.get("name"): (child.text or "").lower()
            for child in sampler.findall("boolProp")
        }
        if values.get("HTTPSampler.method") != method:
            errors.append(f"{name} has wrong method")
        if path_fragment not in values.get("HTTPSampler.path", ""):
            errors.append(f"{name} has wrong path")
        if values.get("HTTPSampler.domain") != "${__P(PERF_HOST,127.0.0.1)}":
            errors.append(f"{name} must use the gated PERF_HOST property")
        if values.get("HTTPSampler.port") != "${__P(PERF_PORT,8080)}":
            errors.append(f"{name} must use the gated PERF_PORT property")
        if values.get("HTTPSampler.protocol") != "${__P(PERF_PROTOCOL,http)}":
            errors.append(f"{name} must use the gated PERF_PROTOCOL property")
        for redirect_property in (
            "HTTPSampler.follow_redirects",
            "HTTPSampler.auto_redirects",
        ):
            if boolean_values.get(redirect_property) != "false":
                errors.append(f"{name} {redirect_property} must be false")

    def paired_hash_tree(element: ET.Element) -> ET.Element | None:
        for tree in root.iter("hashTree"):
            children = list(tree)
            for index, child in enumerate(children[:-1]):
                if child is element and children[index + 1].tag == "hashTree":
                    return children[index + 1]
        return None

    guard_nodes = list(root.iter("IfController"))
    guard_names = [item.attrib.get("testname", "") for item in guard_nodes]
    guards = {item.attrib.get("testname", ""): item for item in guard_nodes}
    if len(set(guard_names)) != len(guard_names):
        errors.append("JMX controller names must be unique")
    expected_guard_conditions = {
        "Static validation guard": (
            "${__groovy(props.get('PERF_VALIDATE_ONLY'\\,'true').toBoolean())}"
        ),
        "Execution guard": (
            "${__groovy(!props.get('PERF_VALIDATE_ONLY'\\,'true').toBoolean())}"
        ),
        "L1 branch": "${__groovy(props.get('PERF_LAYER'\\,'') == 'L1')}",
        "L2 branch": "${__groovy(props.get('PERF_LAYER'\\,'') == 'L2')}",
        "L3 branch": "${__groovy(props.get('PERF_LAYER'\\,'') == 'L3')}",
    }
    for guard_name, expected_condition in expected_guard_conditions.items():
        guard = guards.get(guard_name)
        condition = None if guard is None else guard.find("stringProp")
        if condition is None or (condition.text or "") != expected_condition:
            errors.append(f"{guard_name} must default PERF_VALIDATE_ONLY to true")
        if guard is not None and guard.attrib.get("enabled") != "true":
            errors.append(f"{guard_name} must be enabled")

    static_guard = guards.get("Static validation guard")
    static_tree = paired_hash_tree(static_guard) if static_guard is not None else None
    if static_tree is None or any(static_tree.iter("HTTPSamplerProxy")):
        errors.append("static validation guard must contain no HTTP sampler")
    execution_guard = guards.get("Execution guard")
    execution_tree = paired_hash_tree(execution_guard) if execution_guard is not None else None
    guarded_samplers = (
        {
            item.attrib.get("testname", "")
            for item in execution_tree.iter("HTTPSamplerProxy")
        }
        if execution_tree is not None
        else set()
    )
    if guarded_samplers != set(expected):
        errors.append("all approved HTTP samplers must be nested under the execution guard")

    json_assertion_contracts = {
        "L1-retrieval": (
            ("L1 requires degraded=false", "$.degraded", "false"),
            ("L1 requires status success", "$.status", "success"),
        ),
        "L3-chat": (
            (
                "L3 requires evidence decision ANSWER",
                "$.data.metadata.evidenceDecision.decision",
                "ANSWER",
            ),
        ),
    }
    required_boolean_properties = {
        "JSONVALIDATION": "true",
        "EXPECT_NULL": "false",
        "INVERT": "false",
        "ISREGEX": "false",
    }
    for sampler_name, contracts in json_assertion_contracts.items():
        sampler = samplers.get(sampler_name)
        assertion_tree = paired_hash_tree(sampler) if sampler is not None else None
        assertion_nodes = (
            list(assertion_tree.findall("JSONPathAssertion"))
            if assertion_tree is not None
            else []
        )
        assertion_names = [node.attrib.get("testname", "") for node in assertion_nodes]
        assertions = {
            node.attrib.get("testname", ""): node for node in assertion_nodes
        }
        expected_assertion_names = {contract[0] for contract in contracts}
        if (
            len(assertion_names) != len(set(assertion_names))
            or set(assertions) != expected_assertion_names
        ):
            errors.append(f"{sampler_name} JSON assertions must match the approved set")
        for assertion_name, json_path, expected_value in contracts:
            assertion = assertions.get(assertion_name)
            if assertion is None:
                errors.append(f"{sampler_name} is missing its fail-closed JSON assertion")
                continue
            if assertion.attrib.get("enabled") != "true":
                errors.append(f"{sampler_name} JSON assertion must be enabled")
            string_values = {
                child.attrib.get("name"): child.text or ""
                for child in assertion.findall("stringProp")
            }
            boolean_values = {
                child.attrib.get("name"): (child.text or "").lower()
                for child in assertion.findall("boolProp")
            }
            if string_values.get("JSON_PATH") != json_path:
                errors.append(f"{sampler_name} has the wrong JSON assertion path")
            if string_values.get("EXPECTED_VALUE") != expected_value:
                errors.append(f"{sampler_name} has the wrong JSON assertion value")
            for property_name, required_value in required_boolean_properties.items():
                if boolean_values.get(property_name) != required_value:
                    errors.append(
                        f"{sampler_name} JSON assertion {property_name} must be {required_value}"
                    )

    response_assertion_contracts = {
        "L1-retrieval": ("L1 requires HTTP 200", "200"),
        "L2-ingest-submit": ("L2 submit requires HTTP 202", "202"),
        "L2-ingest-poll": ("L2 poll requires HTTP 200", "200"),
        "L3-chat": ("L3 requires HTTP 200", "200"),
    }
    stop_script = (
        "if (prev != null && prev.getResponseCode() == '429') {\n"
        "    props.put('PERF_STOP_REASON', 'HTTP_429')\n"
        "    prev.setSuccessful(false)\n"
        "    prev.setStopTestNow(true)\n"
        "}"
    )
    for sampler_name, (assertion_name, expected_code) in response_assertion_contracts.items():
        sampler = samplers.get(sampler_name)
        tree = paired_hash_tree(sampler) if sampler is not None else None
        response_nodes = list(tree.findall("ResponseAssertion")) if tree is not None else []
        named_responses = {
            node.attrib.get("testname", ""): node for node in response_nodes
        }
        assertion = named_responses.get(assertion_name)
        if len(response_nodes) != 1 or assertion is None:
            errors.append(f"{sampler_name} must have exactly one approved HTTP status assertion")
        else:
            strings = assertion.find("collectionProp")
            field = assertion.find("stringProp[@name='Assertion.test_field']")
            test_type = assertion.find("intProp[@name='Assertion.test_type']")
            assume_success = assertion.find("boolProp[@name='Assertion.assume_success']")
            codes = [] if strings is None else [(node.text or "") for node in strings.findall("stringProp")]
            if (
                assertion.attrib.get("enabled") != "true"
                or codes != [expected_code]
                or field is None
                or (field.text or "") != "Assertion.response_code"
                or test_type is None
                or (test_type.text or "") != "8"
                or assume_success is None
                or (assume_success.text or "").lower() != "false"
            ):
                errors.append(f"{sampler_name} HTTP status assertion is weakened")

        postprocessors = list(tree.findall("JSR223PostProcessor")) if tree is not None else []
        immediate_name = f"{sampler_name} stop immediately on HTTP 429"
        immediate = [
            node for node in postprocessors
            if node.attrib.get("testname", "") == immediate_name
        ]
        if len(immediate) != 1:
            errors.append(f"{sampler_name} is missing its immediate HTTP 429 stop")
        else:
            script = immediate[0].find("stringProp[@name='script']")
            language = immediate[0].find("stringProp[@name='scriptLanguage']")
            if (
                immediate[0].attrib.get("enabled") != "true"
                or language is None
                or (language.text or "") != "groovy"
                or script is None
                or (script.text or "").strip() != stop_script
            ):
                errors.append(f"{sampler_name} immediate HTTP 429 stop is weakened")

    xml_text = path.read_text(encoding="utf-8")
    if "PERF_VALIDATE_ONLY" not in xml_text:
        errors.append("missing validate-only network guard")
    if "System.getenv('RAG_PERFORMANCE_TOKEN')" not in xml_text:
        errors.append("performance token must come from process environment")
    if "System.getenv('RAG_PERFORMANCE_L1_BODY')" not in xml_text:
        errors.append("L1 body must come from process environment")
    if "System.getenv('RAG_PERFORMANCE_DOCUMENT_ID_POOL')" not in xml_text:
        errors.append("L2 document ID pool must come from process environment")
    if "System.getenv('RAG_PERFORMANCE_L3_BODY')" not in xml_text:
        errors.append("L3 body must come from process environment")

    save_config = root.find(".//ResultCollector/objProp/value")
    plan_nodes = list(root.iter("TestPlan"))
    thread_group_nodes = list(root.iter("ThreadGroup"))
    collector_nodes = list(root.iter("ResultCollector"))
    if (
        len(plan_nodes) != 1
        or plan_nodes[0].attrib.get("enabled") != "true"
        or len(thread_group_nodes) != 1
        or thread_group_nodes[0].attrib.get("enabled") != "true"
        or len(collector_nodes) != 1
        or collector_nodes[0].attrib.get("enabled") != "true"
    ):
        errors.append("JMX plan, thread group, and privacy-safe collector must be uniquely enabled")
    if thread_group_nodes:
        thread_group = thread_group_nodes[0]
        thread_strings = {
            child.attrib.get("name"): child.text or ""
            for child in thread_group.findall("stringProp")
        }
        scheduler = thread_group.find("boolProp[@name='ThreadGroup.scheduler']")
        if (
            thread_strings.get("ThreadGroup.num_threads") != "${__P(PERF_THREADS,1)}"
            or thread_strings.get("ThreadGroup.ramp_time") != "${__P(PERF_RAMP_SECONDS,1)}"
            or thread_strings.get("ThreadGroup.duration")
            != "${__P(PERF_DURATION_SECONDS,1)}"
            or scheduler is None
            or (scheduler.text or "").lower() != "true"
        ):
            errors.append("JMX thread group must use the gated load properties and scheduler")
    if collector_nodes:
        filename = collector_nodes[0].find("stringProp[@name='filename']")
        if filename is None or (filename.text or "") != "${__P(PERF_JTL,)}":
            errors.append("JMX collector must use the gated PERF_JTL property")
    required_false = {
        "message",
        "dataType",
        "encoding",
        "assertions",
        "subresults",
        "responseData",
        "samplerData",
        "xml",
        "responseHeaders",
        "requestHeaders",
        "responseDataOnError",
        "saveAssertionResultsFailureMessage",
        "url",
    }
    if save_config is None:
        errors.append("missing privacy-safe JTL save configuration")
    else:
        for field in required_false:
            child = save_config.find(field)
            if child is None or (child.text or "").lower() != "false":
                errors.append(f"JTL field {field} must be false")
        for field in ("time", "timestamp", "success", "label", "code", "threadName"):
            child = save_config.find(field)
            if child is None or (child.text or "").lower() != "true":
                errors.append(f"JTL field {field} must be true")
    if errors:
        raise WorkflowError("JMX validation failed: " + "; ".join(errors))
    return {"jmeterVersion": EXPECTED_JMETER_VERSION, "samplers": sorted(expected)}


def nearest_rank(values: Sequence[float], percentile: float) -> float | None:
    if not values:
        return None
    if not 0 < percentile <= 1:
        raise ValueError("percentile must be in (0, 1]")
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def _is_5xx(code: str) -> bool:
    return bool(re.fullmatch(r"5\d\d", code))


def aggregate_samples(
    samples: Sequence[Sample],
    layer: str,
    stage: str,
    concurrency: int,
    measurement_duration_seconds: float | None = None,
) -> dict[str, Any]:
    elapsed = [float(sample.elapsed_ms) for sample in samples]
    if samples:
        started = min(sample.timestamp_ms for sample in samples)
        ended = max(sample.timestamp_ms + sample.elapsed_ms for sample in samples)
        sample_span_seconds = max(0.001, (ended - started) / 1000.0)
    else:
        sample_span_seconds = 0.0
    duration_seconds = (
        float(measurement_duration_seconds)
        if measurement_duration_seconds is not None
        else sample_span_seconds
    )
    if duration_seconds < 0 or not math.isfinite(duration_seconds):
        raise RuntimeEvidenceError("measurement duration is invalid")
    errors = sum(1 for sample in samples if not sample.success)
    count = len(samples)
    return {
        "layer": layer,
        "stage": stage,
        "concurrency": concurrency,
        "sampleCount": count,
        "measurementDurationSeconds": duration_seconds,
        "observedSampleSpanSeconds": sample_span_seconds,
        "participatingThreads": len({sample.thread_name for sample in samples}),
        "p50Ms": nearest_rank(elapsed, 0.50),
        "p95Ms": nearest_rank(elapsed, 0.95),
        "p99Ms": nearest_rank(elapsed, 0.99),
        "percentileMethod": "nearest-rank",
        "throughputPerSecond": (count / duration_seconds) if duration_seconds else 0.0,
        "errorRate": (errors / count) if count else 0.0,
        "http429Count": sum(1 for sample in samples if sample.response_code == "429"),
        "http5xxCount": sum(1 for sample in samples if _is_5xx(sample.response_code)),
    }


def read_jtl(path: Path) -> list[Sample]:
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            required = {
                "timeStamp",
                "elapsed",
                "label",
                "responseCode",
                "success",
                "threadName",
            }
            if reader.fieldnames is None or not required.issubset(reader.fieldnames):
                raise RuntimeEvidenceError("JTL is missing required privacy-safe columns")
            samples: list[Sample] = []
            for line_number, row in enumerate(reader, start=2):
                try:
                    samples.append(
                        Sample(
                            timestamp_ms=int(row["timeStamp"]),
                            elapsed_ms=int(row["elapsed"]),
                            label=row["label"],
                            response_code=row["responseCode"],
                            success=row["success"].strip().lower() == "true",
                            thread_name=row["threadName"],
                        )
                    )
                except (KeyError, TypeError, ValueError) as exc:
                    raise RuntimeEvidenceError(f"JTL row {line_number} is invalid") from exc
            return samples
    except OSError as exc:
        raise RuntimeEvidenceError("JTL could not be read") from exc


def aggregate_jtl(
    path: Path,
    layer: str,
    concurrency: int,
    measurement_duration_seconds: float | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[Sample]]:
    samples = read_jtl(path)
    primary_label = PRIMARY_LABELS[layer]
    primary = [sample for sample in samples if sample.label == primary_label]
    result = aggregate_samples(
        primary,
        layer,
        primary_label,
        concurrency,
        measurement_duration_seconds,
    )
    stages = [
        aggregate_samples(
            [sample for sample in samples if sample.label == label],
            layer,
            label,
            concurrency,
            measurement_duration_seconds,
        )
        for label in sorted({sample.label for sample in samples})
    ]
    return result, stages, samples


def consecutive_window_p99_breach(
    samples: Sequence[Sample],
    baseline_p99_ms: float | None,
    multiplier: float,
    required_windows: int,
    window_seconds: int,
) -> bool:
    if not samples or baseline_p99_ms is None or baseline_p99_ms <= 0:
        return False
    started = min(sample.timestamp_ms for sample in samples)
    buckets: dict[int, list[float]] = {}
    window_ms = window_seconds * 1000
    for sample in samples:
        bucket = (sample.timestamp_ms - started) // window_ms
        buckets.setdefault(bucket, []).append(float(sample.elapsed_ms))
    completed_bucket_count = int(
        max(0, max(sample.timestamp_ms + sample.elapsed_ms for sample in samples) - started)
        // window_ms
    )
    if completed_bucket_count < required_windows:
        return False
    consecutive = 0
    threshold = baseline_p99_ms * multiplier
    for bucket in range(completed_bucket_count):
        values = buckets.get(bucket)
        if not values:
            consecutive = 0
            continue
        p99 = nearest_rank(values, 0.99)
        if p99 is not None and p99 > threshold:
            consecutive += 1
            if consecutive >= required_windows:
                return True
        else:
            consecutive = 0
    return False


def evaluate_window_stop(
    result: Mapping[str, Any],
    all_samples: Sequence[Sample],
    primary_samples: Sequence[Sample],
    stop_conditions: Mapping[str, Any],
    baseline_p99_ms: float | None,
    analysis_window_seconds: int,
) -> dict[str, str] | None:
    if stop_conditions["stopOnAny429"] and any(sample.response_code == "429" for sample in all_samples):
        return {"kind": "HTTP_429", "detail": "at least one HTTP 429 was observed"}
    if result["errorRate"] > stop_conditions["errorRateGreaterThan"]:
        return {"kind": "ERROR_RATE", "detail": "error rate exceeded the configured threshold"}
    if consecutive_window_p99_breach(
        primary_samples,
        baseline_p99_ms,
        stop_conditions["p99MultiplierOverLowestConcurrency"],
        stop_conditions["p99ConsecutiveWindows"],
        analysis_window_seconds,
    ):
        return {"kind": "P99_CONSECUTIVE_WINDOWS", "detail": "P99 exceeded the baseline multiplier for consecutive windows"}
    return None


def immediate_jtl_stop_reason(
    all_samples: Sequence[Sample],
    stop_conditions: Mapping[str, Any],
) -> dict[str, str] | None:
    if stop_conditions["stopOnAny429"] and any(
        sample.response_code == "429" for sample in all_samples
    ):
        return {
            "kind": "HTTP_429",
            "detail": "at least one HTTP 429 was observed and JMeter stopped immediately",
        }
    return None


def evaluate_resource_stop(
    cpu_ratio: float,
    memory_ratio: float,
    sampled_at_seconds: float,
    stop_conditions: Mapping[str, Any],
    state: ResourceStopState,
    maximum_sample_gap_seconds: float | None = None,
) -> dict[str, str] | None:
    if memory_ratio > stop_conditions["memoryGreaterThan"]:
        return {"kind": "MEMORY", "detail": "memory ratio exceeded the configured threshold"}
    previous_sample = state.last_sampled_seconds
    if (
        previous_sample is not None
        and (
            sampled_at_seconds < previous_sample
            or (
                maximum_sample_gap_seconds is not None
                and sampled_at_seconds - previous_sample > maximum_sample_gap_seconds
            )
        )
    ):
        state.cpu_above_started_seconds = None
    state.last_sampled_seconds = sampled_at_seconds
    if cpu_ratio <= stop_conditions["cpuGreaterThan"]:
        state.cpu_above_started_seconds = None
        return None
    started = state.cpu_above_started_seconds
    if started is None or sampled_at_seconds < started:
        state.cpu_above_started_seconds = sampled_at_seconds
        return None
    if sampled_at_seconds - started >= stop_conditions["cpuConsecutiveSeconds"]:
        return {
            "kind": "CPU",
            "detail": "CPU ratio exceeded the configured threshold for the consecutive-duration limit",
        }
    return None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


def _http_json(base_url: str, path: str, timeout_seconds: float = 5.0) -> dict[str, Any]:
    url = base_url.rstrip("/") + path
    request = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                raise RuntimeEvidenceError("Actuator returned a non-200 status")
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise RuntimeEvidenceError("Actuator response exceeded the safety limit")
            value = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError, urllib.error.HTTPError) as exc:
        raise RuntimeEvidenceError("Actuator endpoint is unavailable or invalid") from exc
    if not isinstance(value, dict):
        raise RuntimeEvidenceError("Actuator response is not an object")
    return value


def _metric_value(
    payload: Mapping[str, Any],
    accepted_statistics: frozenset[str] = frozenset({"VALUE"}),
) -> float:
    measurements = payload.get("measurements")
    if not isinstance(measurements, list):
        raise RuntimeEvidenceError("Actuator metric has no measurements")
    values = [
        item.get("value")
        for item in measurements
        if isinstance(item, dict)
        and item.get("statistic") in accepted_statistics
        and _is_number(item.get("value"))
    ]
    if not values:
        raise RuntimeEvidenceError("Actuator metric has no accepted numeric measurement")
    value = float(sum(values))
    if not math.isfinite(value) or value < 0:
        raise RuntimeEvidenceError("Actuator metric VALUE is invalid")
    return value


def actuator_preflight(base_url: str, actuator: Mapping[str, Any]) -> tuple[float, float]:
    health = _http_json(base_url, actuator["healthPath"])
    if health.get("status") != "UP":
        raise RuntimeEvidenceError("Actuator health is not UP")
    return actuator_resource_sample(base_url, actuator)


def actuator_runtime_build_fingerprint(
    base_url: str,
    actuator: Mapping[str, Any],
    expected_sha256: str,
) -> str:
    info = _http_json(base_url, actuator["infoPath"])
    rag = info.get("rag")
    performance = rag.get("performance") if isinstance(rag, dict) else None
    observed = (
        performance.get("runtimeBuildFingerprintSha256")
        if isinstance(performance, dict)
        else None
    )
    if (
        not isinstance(observed, str)
        or re.fullmatch(r"[0-9a-f]{64}", observed) is None
        or not secrets.compare_digest(observed, expected_sha256)
    ):
        raise RuntimeEvidenceError(
            "Actuator runtime build fingerprint is absent or does not match the authorized profile"
        )
    return observed


def actuator_resource_sample(base_url: str, actuator: Mapping[str, Any]) -> tuple[float, float]:
    cpu = _metric_value(_http_json(base_url, actuator["cpuMetricPath"]))
    used = _metric_value(_http_json(base_url, actuator["memoryUsedMetricPath"]))
    maximum = _metric_value(_http_json(base_url, actuator["memoryMaxMetricPath"]))
    if maximum <= 0:
        raise RuntimeEvidenceError("Actuator memory maximum is not positive")
    memory = used / maximum
    if not math.isfinite(memory) or memory < 0:
        raise RuntimeEvidenceError("Actuator memory ratio is invalid")
    return cpu, memory


def external_call_counter(base_url: str, external: Mapping[str, Any]) -> float:
    path = external.get("zeroExternalCallMetricPath")
    if not isinstance(path, str) or not path:
        raise RuntimeEvidenceError("zero-external-call metric is not configured")
    return _metric_value(
        _http_json(base_url, path),
        frozenset({"COUNT", "VALUE"}),
    )


def create_run_directory(output_root: Path, run_id: str) -> Path:
    resolved_root = output_root.resolve()
    if resolved_root == Path(resolved_root.anchor):
        raise WorkflowError("output root cannot be a filesystem root")
    resolved_root.mkdir(parents=True, exist_ok=True)
    run_dir = resolved_root / run_id
    run_dir.mkdir(exist_ok=False)
    return run_dir


def git_snapshot(repo_root: Path) -> dict[str, Any]:
    def git(*args: str) -> bytes:
        completed = subprocess.run(
            ["git", *args],
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if completed.returncode != 0:
            raise WorkflowError("Git provenance command failed")
        return completed.stdout

    head = git("rev-parse", "HEAD").decode("ascii", "strict").strip()
    status = git("status", "--porcelain=v1", "-z", "--untracked-files=all")
    index_manifest = git("ls-files", "-s", "-z")
    paths_raw = git("ls-files", "-z", "--modified", "--deleted", "--others", "--exclude-standard")
    digest = hashlib.sha256()
    digest.update(status)
    digest.update(b"\0index\0")
    digest.update(index_manifest)
    paths = sorted({item for item in paths_raw.split(b"\0") if item})
    for raw_path in paths:
        digest.update(b"\0path\0")
        digest.update(raw_path)
        relative = raw_path.decode("utf-8", "surrogateescape")
        target = repo_root / relative
        if target.is_file():
            digest.update(b"\0file\0")
            with target.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        elif target.exists():
            digest.update(b"\0non-file\0")
        else:
            digest.update(b"\0deleted\0")
    return {
        "gitHead": head,
        "gitDirty": bool(status),
        "gitDirtyFingerprint": digest.hexdigest(),
    }


def discover_jmeter(explicit: str | None, environ: Mapping[str, str]) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    home = environ.get("JMETER_HOME")
    if home:
        candidates.extend([Path(home) / "bin" / "jmeter.bat", Path(home) / "bin" / "jmeter"])
    which = shutil.which("jmeter") or shutil.which("jmeter.bat")
    if which:
        candidates.append(Path(which))
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise WorkflowError("Apache JMeter executable was not found")


def _native_command(executable: Path, arguments: Sequence[str]) -> list[str]:
    if os.name == "nt" and executable.suffix.lower() in {".bat", ".cmd"}:
        command_line = subprocess.list2cmdline([str(executable), *arguments])
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", command_line]
    return [str(executable), *arguments]


def jmeter_version(jmeter: Path) -> str:
    completed = subprocess.run(
        _native_command(jmeter, ["--version"]),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        timeout=30,
    )
    if completed.returncode != 0:
        raise WorkflowError("JMeter version check failed")
    match = re.search(r"\b(\d+\.\d+\.\d+)\b", completed.stdout)
    if match is None or match.group(1) != EXPECTED_JMETER_VERSION:
        raise WorkflowError("Apache JMeter 5.6.3 is required")
    return match.group(1)


def jmeter_arguments(
    jmx: Path,
    jtl: Path,
    base_url: str,
    layer: str,
    concurrency: int,
    duration_seconds: int,
    ramp_seconds: int,
    poll_interval_ms: int,
    poll_timeout_seconds: int,
) -> list[str]:
    parsed = urllib.parse.urlsplit(base_url)
    default_port = 443 if parsed.scheme == "https" else 80
    return [
        "-n",
        "-t",
        str(jmx),
        "-j",
        str(jtl.with_suffix(".engine.log")),
        "-JPERF_VALIDATE_ONLY=false",
        f"-JPERF_LAYER={layer}",
        f"-JPERF_THREADS={concurrency}",
        f"-JPERF_DURATION_SECONDS={duration_seconds}",
        f"-JPERF_RAMP_SECONDS={ramp_seconds}",
        f"-JPERF_PROTOCOL={parsed.scheme}",
        f"-JPERF_HOST={parsed.hostname}",
        f"-JPERF_PORT={parsed.port or default_port}",
        f"-JPERF_JTL={jtl}",
        f"-JPERF_POLL_INTERVAL_MS={poll_interval_ms}",
        f"-JPERF_POLL_TIMEOUT_MS={poll_timeout_seconds * 1000}",
    ]


def _terminate_process(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def run_jmeter_invocation(
    jmeter: Path,
    arguments: Sequence[str],
    run_dir: Path,
    resource_sampler: Callable[[], tuple[float, float]],
    sample_interval_seconds: float,
    stop_conditions: Mapping[str, Any],
    resource_state: dict[str, Any],
    process_environment: Mapping[str, str],
    resource_stop_state: ResourceStopState,
    run_started_monotonic: float,
) -> InvocationResult:
    log_path: Path | None = None
    try:
        log_index = list(arguments).index("-j")
        log_path = Path(arguments[log_index + 1])
    except (ValueError, IndexError):
        raise WorkflowError("JMeter invocation is missing its isolated log path")
    try:
        process = subprocess.Popen(
            _native_command(jmeter, arguments),
            cwd=run_dir,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=dict(process_environment),
        )
    except OSError as exc:
        if log_path is not None:
            log_path.unlink(missing_ok=True)
        raise RuntimeEvidenceError("JMeter process could not start") from exc
    started = time.monotonic()
    try:
        while process.poll() is None:
            try:
                cpu, memory = resource_sampler()
            except RuntimeEvidenceError:
                _terminate_process(process)
                resource_state["status"] = "FAILED"
                resource_state["failureReason"] = "Actuator resource metric became unavailable"
                raise
            sampled_at_seconds = max(
                0.0,
                time.monotonic() - run_started_monotonic,
            )
            resource_state["samples"].append(
                {
                    "offsetSeconds": sampled_at_seconds,
                    "cpuRatio": cpu,
                    "memoryRatio": memory,
                }
            )
            resource_state["peakCpuRatio"] = max(resource_state["peakCpuRatio"] or 0.0, cpu)
            resource_state["peakMemoryRatio"] = max(resource_state["peakMemoryRatio"] or 0.0, memory)
            resource_state["status"] = "MEASURED"
            stop = evaluate_resource_stop(
                cpu,
                memory,
                sampled_at_seconds,
                stop_conditions,
                resource_stop_state,
                sample_interval_seconds * 1.5,
            )
            if stop is not None:
                _terminate_process(process)
                return InvocationResult(
                    stop_reason=stop,
                    duration_seconds=max(0.0, time.monotonic() - started),
                )
            deadline = time.monotonic() + sample_interval_seconds
            while process.poll() is None and time.monotonic() < deadline:
                time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        if process.returncode != 0:
            raise RuntimeEvidenceError("JMeter process failed")
        return InvocationResult(
            stop_reason=None,
            duration_seconds=max(0.0, time.monotonic() - started),
        )
    finally:
        if process.poll() is None:
            _terminate_process(process)
        if log_path is not None:
            try:
                log_path.unlink(missing_ok=True)
            except OSError as exc:
                raise RuntimeEvidenceError("isolated JMeter log could not be removed") from exc


def unknown_provider_usage() -> dict[str, Any]:
    return {
        "status": "UNKNOWN",
        "provider": None,
        "model": None,
        "requestCount": None,
        "inputTokens": None,
        "outputTokens": None,
        "totalTokens": None,
        "costUsd": None,
        "pricingVersion": None,
    }


def sample_coverage_seconds(samples: Sequence[Sample]) -> float:
    if not samples:
        return 0.0
    started = min(sample.timestamp_ms for sample in samples)
    ended = max(sample.timestamp_ms + sample.elapsed_ms for sample in samples)
    return max(0.0, (ended - started) / 1000.0)


def require_window_coverage(
    samples: Sequence[Sample],
    expected_seconds: int,
    phase: str,
) -> None:
    if sample_coverage_seconds(samples) < expected_seconds * 0.90:
        raise RuntimeEvidenceError(
            f"{phase} ended early before the configured duration"
        )


def require_window_evidence(
    samples: Sequence[Sample],
    expected_seconds: int,
    expected_concurrency: int,
    invocation_duration_seconds: float,
    phase: str,
) -> None:
    if invocation_duration_seconds < expected_seconds * 0.90:
        raise RuntimeEvidenceError(
            f"{phase} JMeter invocation ended early before the configured duration"
        )
    require_window_coverage(samples, expected_seconds, phase)
    thread_names = {sample.thread_name for sample in samples if sample.thread_name.strip()}
    if len(thread_names) != expected_concurrency:
        raise RuntimeEvidenceError(
            f"{phase} did not include every configured JMeter thread"
        )
    reference_start_ms = min(sample.timestamp_ms for sample in samples)
    grace_ms = max(1000.0, expected_seconds * 100.0)
    required_end_ms = reference_start_ms + expected_seconds * 900.0
    maximum_inactive_gap_ms = grace_ms
    for thread_name in thread_names:
        thread_samples = sorted(
            (sample for sample in samples if sample.thread_name == thread_name),
            key=lambda sample: (sample.timestamp_ms, sample.elapsed_ms),
        )
        if thread_samples[0].timestamp_ms > reference_start_ms + grace_ms:
            raise RuntimeEvidenceError(
                f"{phase} thread joined too late for a stable window"
            )
        cursor_ms = float(thread_samples[0].timestamp_ms)
        for sample in thread_samples:
            if sample.timestamp_ms - cursor_ms > maximum_inactive_gap_ms:
                raise RuntimeEvidenceError(
                    f"{phase} contains an unsupported inactive thread gap"
                )
            cursor_ms = max(
                cursor_ms,
                float(sample.timestamp_ms + sample.elapsed_ms),
            )
        if cursor_ms < required_end_ms:
            raise RuntimeEvidenceError(
                f"{phase} thread did not remain active through the stable window"
            )


def _environment_summary() -> dict[str, str]:
    return {
        "operatingSystem": platform.system(),
        "operatingSystemRelease": platform.release(),
        "machine": platform.machine(),
        "pythonVersion": platform.python_version(),
    }


def base_report(provenance: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "runStatus": "FAILED",
        "evidenceLevel": "RUNTIME",
        "runtimeEvidenceStatus": "INVALID",
        "thresholdStatus": "INVALID",
        "results": [],
        "stopReason": {
            "kind": "RUNTIME_EVIDENCE",
            "detail": "execution did not reach a valid terminal state",
        },
        "resource": {
            "status": "UNKNOWN",
            "samples": [],
            "peakCpuRatio": None,
            "peakMemoryRatio": None,
            "failureReason": None,
        },
        "samplerMetrics": [],
        "internalStageMetrics": {
            "status": "NOT_COLLECTED",
            "metrics": [],
            "reason": (
                "JMeter sampler aggregates are collected separately; internal "
                "retrieval/rerank/LLM percentile scraping is not implemented"
            ),
        },
        "providerUsage": unknown_provider_usage(),
        "provenance": dict(provenance),
    }


def _require_non_negative_number(value: Any, field: str) -> None:
    if not _is_number(value) or value < 0:
        raise WorkflowError(f"report {field} must be a non-negative finite number")


def _validate_metric_set(metric: Any) -> None:
    required = {
        "layer",
        "stage",
        "concurrency",
        "sampleCount",
        "measurementDurationSeconds",
        "observedSampleSpanSeconds",
        "participatingThreads",
        "p50Ms",
        "p95Ms",
        "p99Ms",
        "percentileMethod",
        "throughputPerSecond",
        "errorRate",
        "http429Count",
        "http5xxCount",
    }
    if not isinstance(metric, dict) or set(metric) != required:
        raise WorkflowError("report metric fields do not match schema v1")
    if metric["layer"] not in {"L1", "L2", "L3"}:
        raise WorkflowError("report metric layer is invalid")
    if not isinstance(metric["stage"], str) or not metric["stage"]:
        raise WorkflowError("report metric stage is invalid")
    for field, minimum in (
        ("concurrency", 1),
        ("sampleCount", 0),
        ("participatingThreads", 0),
        ("http429Count", 0),
        ("http5xxCount", 0),
    ):
        value = metric[field]
        if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
            raise WorkflowError(f"report metric {field} is invalid")
    for field in (
        "measurementDurationSeconds",
        "observedSampleSpanSeconds",
        "throughputPerSecond",
    ):
        _require_non_negative_number(metric[field], f"metric.{field}")
    for field in ("p50Ms", "p95Ms", "p99Ms"):
        value = metric[field]
        if value is not None:
            _require_non_negative_number(value, f"metric.{field}")
    if metric["percentileMethod"] != "nearest-rank":
        raise WorkflowError("report percentile method is invalid")
    if not _is_number(metric["errorRate"]) or not 0 <= metric["errorRate"] <= 1:
        raise WorkflowError("report metric error rate is invalid")


def _validate_provenance(provenance: Any) -> None:
    required = {
        "gitHead",
        "gitDirty",
        "gitDirtyFingerprint",
        "gitEndHead",
        "gitEndDirty",
        "gitEndDirtyFingerprint",
        "sourceDriftStatus",
        "profileSha256",
        "jmxSha256",
        "schemaSha256",
        "fixtureFingerprintSha256",
        "runtimeBuildFingerprintSha256",
        "runtimeIdentityStatus",
        "jmeterVersion",
        "jmeterExecutableSha256",
        "environment",
    }
    if not isinstance(provenance, dict) or set(provenance) != required:
        raise WorkflowError("report provenance fields do not match schema v1")
    for field in ("gitHead", "gitEndHead"):
        if not isinstance(provenance[field], str) or re.fullmatch(
            r"[0-9a-f]{40}", provenance[field]
        ) is None:
            raise WorkflowError(f"report provenance {field} is invalid")
    for field in (
        "gitDirtyFingerprint",
        "gitEndDirtyFingerprint",
        "profileSha256",
        "jmxSha256",
        "schemaSha256",
        "fixtureFingerprintSha256",
        "runtimeBuildFingerprintSha256",
        "jmeterExecutableSha256",
    ):
        if not isinstance(provenance[field], str) or re.fullmatch(
            r"[0-9a-f]{64}", provenance[field]
        ) is None:
            raise WorkflowError(f"report provenance {field} is invalid")
    if not isinstance(provenance["gitDirty"], bool) or not isinstance(
        provenance["gitEndDirty"], bool
    ):
        raise WorkflowError("report provenance dirty flags are invalid")
    if provenance["sourceDriftStatus"] not in {"STABLE", "DRIFTED"}:
        raise WorkflowError("report provenance drift status is invalid")
    if provenance["runtimeIdentityStatus"] not in {
        "NOT_CHECKED",
        "MATCHED",
        "FAILED",
    }:
        raise WorkflowError("report runtime identity status is invalid")
    if provenance["jmeterVersion"] != EXPECTED_JMETER_VERSION:
        raise WorkflowError("report JMeter version is invalid")
    if not isinstance(provenance["environment"], dict):
        raise WorkflowError("report environment summary is invalid")


def validate_report_shape(report: Mapping[str, Any]) -> None:
    required = {
        "schemaVersion",
        "runStatus",
        "evidenceLevel",
        "runtimeEvidenceStatus",
        "thresholdStatus",
        "results",
        "stopReason",
        "resource",
        "samplerMetrics",
        "internalStageMetrics",
        "providerUsage",
        "provenance",
    }
    if set(report) != required:
        raise WorkflowError("report fields do not match schema v1")
    if report["schemaVersion"] != 1 or report["runStatus"] not in VALID_RUN_STATUSES:
        raise WorkflowError("report status is invalid")
    if report["evidenceLevel"] not in VALID_EVIDENCE_LEVELS:
        raise WorkflowError("report evidence level is invalid")
    if report["runtimeEvidenceStatus"] not in VALID_RUNTIME_EVIDENCE_STATUSES:
        raise WorkflowError("runtime evidence status is invalid")
    if report["thresholdStatus"] not in VALID_THRESHOLD_STATUSES:
        raise WorkflowError("threshold status is invalid")
    status_tuple = (
        report["runStatus"],
        report["runtimeEvidenceStatus"],
        report["thresholdStatus"],
    )
    allowed_status_tuples = {
        ("NOT_RUN", "NOT_RUN", "NOT_EVALUATED"),
        ("COMPLETED", "VALID", "WITHIN_PROFILE"),
        ("STOPPED", "VALID", "STOPPED_BY_THRESHOLD"),
        ("FAILED", "INVALID", "INVALID"),
    }
    if status_tuple not in allowed_status_tuples:
        raise WorkflowError("report run/evidence/threshold statuses are inconsistent")
    if not isinstance(report["results"], list) or not isinstance(
        report["samplerMetrics"], list
    ):
        raise WorkflowError("report metric collections must be arrays")
    for metric in [*report["results"], *report["samplerMetrics"]]:
        _validate_metric_set(metric)
    stop_reason = report["stopReason"]
    if stop_reason is not None and (
        not isinstance(stop_reason, dict)
        or set(stop_reason) != {"kind", "detail"}
        or not all(isinstance(stop_reason[key], str) and stop_reason[key] for key in stop_reason)
    ):
        raise WorkflowError("report stop reason is invalid")
    if report["runStatus"] == "COMPLETED" and stop_reason is not None:
        raise WorkflowError("completed report cannot contain a stop reason")
    if report["runStatus"] == "NOT_RUN" and stop_reason is not None:
        raise WorkflowError("NOT_RUN report cannot contain a stop reason")
    if report["runStatus"] == "STOPPED" and (
        stop_reason is None
        or stop_reason["kind"] not in THRESHOLD_STOP_REASON_KINDS
    ):
        raise WorkflowError("stopped report requires a threshold stop reason")
    if report["runStatus"] == "FAILED" and (
        stop_reason is None
        or stop_reason["kind"] not in FAILURE_STOP_REASON_KINDS
    ):
        raise WorkflowError("failed report requires a failure stop reason")
    if report["runStatus"] == "COMPLETED" and (
        not report["results"] or not report["samplerMetrics"]
    ):
        raise WorkflowError("completed report requires measured result collections")
    for metric in report["results"]:
        if metric["sampleCount"] <= 0:
            raise WorkflowError("report result requires at least one sample")
        if metric["participatingThreads"] != metric["concurrency"]:
            raise WorkflowError(
                "report result must include every configured JMeter thread"
            )

    resource = report["resource"]
    resource_fields = {
        "status",
        "samples",
        "peakCpuRatio",
        "peakMemoryRatio",
        "failureReason",
    }
    if not isinstance(resource, dict) or set(resource) != resource_fields:
        raise WorkflowError("report resource fields do not match schema v1")
    if resource["status"] not in {"UNKNOWN", "MEASURED", "FAILED"}:
        raise WorkflowError("report resource status is invalid")
    if not isinstance(resource["samples"], list):
        raise WorkflowError("report resource samples must be an array")
    previous_offset = -1.0
    for sample in resource["samples"]:
        if not isinstance(sample, dict) or set(sample) != {
            "offsetSeconds",
            "cpuRatio",
            "memoryRatio",
        }:
            raise WorkflowError("report resource sample is invalid")
        for field in ("offsetSeconds", "cpuRatio", "memoryRatio"):
            _require_non_negative_number(sample[field], f"resource.{field}")
        if sample["offsetSeconds"] < previous_offset:
            raise WorkflowError("report resource offsets must be monotonic")
        previous_offset = sample["offsetSeconds"]
    for field in ("peakCpuRatio", "peakMemoryRatio"):
        if resource[field] is not None:
            _require_non_negative_number(resource[field], f"resource.{field}")
    if resource["failureReason"] is not None and not isinstance(
        resource["failureReason"], str
    ):
        raise WorkflowError("report resource failure reason is invalid")
    if resource["status"] == "UNKNOWN" and (
        resource["samples"]
        or resource["peakCpuRatio"] is not None
        or resource["peakMemoryRatio"] is not None
        or resource["failureReason"] is not None
    ):
        raise WorkflowError("UNKNOWN resource evidence must remain empty")
    if resource["status"] == "MEASURED" and (
        not resource["samples"]
        or resource["peakCpuRatio"] is None
        or resource["peakMemoryRatio"] is None
        or resource["failureReason"] is not None
    ):
        raise WorkflowError("MEASURED resource evidence is incomplete")
    if resource["status"] == "FAILED" and (
        not isinstance(resource["failureReason"], str)
        or not resource["failureReason"]
    ):
        raise WorkflowError("FAILED resource evidence requires a failure reason")
    if report["runtimeEvidenceStatus"] == "VALID" and resource["status"] != "MEASURED":
        raise WorkflowError("valid runtime evidence requires measured resources")

    internal = report["internalStageMetrics"]
    if not isinstance(internal, dict) or set(internal) != {"status", "metrics", "reason"}:
        raise WorkflowError("report internal stage metric fields do not match schema v1")
    if internal["status"] not in {"NOT_COLLECTED", "MEASURED"} or not isinstance(
        internal["metrics"], list
    ):
        raise WorkflowError("report internal stage metric status is invalid")
    for metric in internal["metrics"]:
        _validate_metric_set(metric)
    if internal["status"] == "NOT_COLLECTED" and (
        internal["metrics"]
        or not isinstance(internal["reason"], str)
        or not internal["reason"]
    ):
        raise WorkflowError("NOT_COLLECTED internal stage metrics require an explicit reason")
    if internal["status"] == "MEASURED" and internal["reason"] is not None:
        raise WorkflowError("MEASURED internal stage metrics require a null reason")
    usage = report["providerUsage"]
    usage_fields = {
        "status",
        "provider",
        "model",
        "requestCount",
        "inputTokens",
        "outputTokens",
        "totalTokens",
        "costUsd",
        "pricingVersion",
    }
    if not isinstance(usage, dict) or set(usage) != usage_fields:
        raise WorkflowError("report provider usage fields do not match schema v1")
    if usage["status"] == "UNKNOWN":
        for key in (
            "provider",
            "model",
            "requestCount",
            "inputTokens",
            "outputTokens",
            "totalTokens",
            "costUsd",
            "pricingVersion",
        ):
            if usage[key] is not None:
                raise WorkflowError("UNKNOWN provider usage must propagate null values")
    elif usage["status"] != "MEASURED":
        raise WorkflowError("report provider usage status is invalid")

    _validate_provenance(report["provenance"])
    provenance = report["provenance"]
    if report["runStatus"] in {"COMPLETED", "STOPPED"} and (
        provenance["sourceDriftStatus"] != "STABLE"
        or provenance["runtimeIdentityStatus"] != "MATCHED"
    ):
        raise WorkflowError("valid runtime report requires stable sources and matched identity")


def _assert_no_sensitive_report_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized = str(key).lower().replace("_", "")
            if normalized in FORBIDDEN_PROFILE_KEYS:
                raise WorkflowError("report contains a forbidden sensitive field")
            _assert_no_sensitive_report_keys(nested)
    elif isinstance(value, list):
        for nested in value:
            _assert_no_sensitive_report_keys(nested)


def write_report_exclusive(
    run_dir: Path,
    report: Mapping[str, Any],
    schema_path: Path | None = None,
) -> Path:
    contract_path = (
        Path(__file__).resolve().with_name("performance-report-v1.schema.json")
        if schema_path is None
        else schema_path
    )
    schema_hash = validate_report_schema_contract(contract_path)
    if report.get("provenance", {}).get("schemaSha256") != schema_hash:
        raise WorkflowError("report provenance does not match the validated schema")
    validate_report_shape(report)
    _assert_no_sensitive_report_keys(report)
    report_path = run_dir / "performance-report.json"
    with report_path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    return report_path


def execute_workflow(
    repo_root: Path,
    profile_path: Path,
    jmx_path: Path,
    schema_path: Path,
    output_root: Path,
    jmeter_path: str | None,
    environ: Mapping[str, str] | None = None,
    explicit_execute: bool = False,
) -> Path:
    environment = dict(os.environ if environ is None else environ)
    profile = load_json_object(profile_path)
    validate_execution_gate(profile, explicit_execute=explicit_execute)
    validate_jmx(jmx_path)
    validate_report_schema_contract(schema_path)
    validate_runtime_environment(profile, environment)
    fixture_fingerprint = verify_runtime_fixture_fingerprint(profile, environment)
    jmeter = discover_jmeter(jmeter_path, environment)
    version = jmeter_version(jmeter)
    try:
        jmeter_hash = sha256_file(jmeter)
    except OSError as exc:
        raise WorkflowError("JMeter executable could not be fingerprinted") from exc
    start_git = git_snapshot(repo_root)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + secrets.token_hex(4)
    run_dir = create_run_directory(output_root, run_id)
    profile_snapshot = run_dir / "performance-profile.snapshot.json"
    jmx_snapshot = run_dir / "rag-layered-performance.snapshot.jmx"
    schema_snapshot = run_dir / "performance-report-v1.snapshot.schema.json"
    profile_hash = snapshot_file_exclusive(profile_path, profile_snapshot)
    jmx_hash = snapshot_file_exclusive(jmx_path, jmx_snapshot)
    schema_hash = snapshot_file_exclusive(schema_path, schema_snapshot)

    profile = load_json_object(profile_snapshot)
    validate_execution_gate(profile, explicit_execute=explicit_execute)
    validate_jmx(jmx_snapshot)
    validate_report_schema_contract(schema_snapshot)
    validate_runtime_environment(profile, environment)
    if verify_runtime_fixture_fingerprint(profile, environment) != fixture_fingerprint:
        raise WorkflowError("runtime workload changed while artifacts were snapshotted")

    provenance = dict(start_git)
    provenance.update(
        {
            "gitEndHead": start_git["gitHead"],
            "gitEndDirty": start_git["gitDirty"],
            "gitEndDirtyFingerprint": start_git["gitDirtyFingerprint"],
            "sourceDriftStatus": "STABLE",
            "profileSha256": profile_hash,
            "jmxSha256": jmx_hash,
            "schemaSha256": schema_hash,
            "fixtureFingerprintSha256": fixture_fingerprint,
            "runtimeBuildFingerprintSha256": profile["workload"][
                "runtimeBuildFingerprintSha256"
            ],
            "runtimeIdentityStatus": "NOT_CHECKED",
            "jmeterVersion": version,
            "jmeterExecutableSha256": jmeter_hash,
            "environment": _environment_summary(),
        }
    )
    report = base_report(provenance)
    load = profile["load"]
    stops = profile["stopConditions"]
    actuator = profile["actuator"]
    base_url = profile["baseUrl"]
    actuator_base_url = actuator["baseUrl"]
    external = profile["external"]
    runtime_build_fingerprint = profile["workload"]["runtimeBuildFingerprintSha256"]
    resource_stop_state = ResourceStopState()
    run_started_monotonic = time.monotonic()

    def set_threshold_stop(reason: dict[str, str]) -> None:
        report["runStatus"] = "STOPPED"
        report["runtimeEvidenceStatus"] = "VALID"
        report["thresholdStatus"] = "STOPPED_BY_THRESHOLD"
        report["stopReason"] = reason

    def source_hash_or_none(path: Path) -> str | None:
        try:
            return sha256_file(path)
        except OSError:
            return None

    def runtime_artifacts_are_stable() -> bool:
        return (
            source_hash_or_none(profile_snapshot) == profile_hash
            and source_hash_or_none(jmx_snapshot) == jmx_hash
            and source_hash_or_none(schema_snapshot) == schema_hash
            and source_hash_or_none(jmeter) == jmeter_hash
        )

    def require_runtime_artifacts_stable() -> None:
        if not runtime_artifacts_are_stable():
            raise RuntimeEvidenceError(
                "snapshotted performance artifacts or JMeter executable changed during the run"
            )

    def finish_report() -> Path:
        try:
            actuator_runtime_build_fingerprint(
                actuator_base_url,
                actuator,
                runtime_build_fingerprint,
            )
            report["provenance"]["runtimeIdentityStatus"] = "MATCHED"
        except RuntimeEvidenceError:
            report["provenance"]["runtimeIdentityStatus"] = "FAILED"
            report["runStatus"] = "FAILED"
            report["runtimeEvidenceStatus"] = "INVALID"
            report["thresholdStatus"] = "INVALID"
            report["stopReason"] = {
                "kind": "RUNTIME_IDENTITY",
                "detail": "runtime build identity was unavailable or changed",
            }

        end_git = git_snapshot(repo_root)
        report["provenance"].update(
            {
                "gitEndHead": end_git["gitHead"],
                "gitEndDirty": end_git["gitDirty"],
                "gitEndDirtyFingerprint": end_git["gitDirtyFingerprint"],
            }
        )
        source_drift = (
            source_hash_or_none(profile_path) != profile_hash
            or source_hash_or_none(jmx_path) != jmx_hash
            or source_hash_or_none(schema_path) != schema_hash
            or not runtime_artifacts_are_stable()
            or end_git != start_git
        )
        if source_drift:
            report["provenance"]["sourceDriftStatus"] = "DRIFTED"
            report["runStatus"] = "FAILED"
            report["runtimeEvidenceStatus"] = "INVALID"
            report["thresholdStatus"] = "INVALID"
            report["stopReason"] = {
                "kind": "SOURCE_DRIFT",
                "detail": "Git, JMeter, or performance artifacts changed during the run",
            }
        report_schema = (
            schema_snapshot
            if source_hash_or_none(schema_snapshot) == schema_hash
            else schema_path
        )
        return write_report_exclusive(run_dir, report, report_schema)

    try:
        actuator_runtime_build_fingerprint(
            actuator_base_url,
            actuator,
            runtime_build_fingerprint,
        )
        report["provenance"]["runtimeIdentityStatus"] = "MATCHED"
        cpu, memory = actuator_preflight(actuator_base_url, actuator)
        external_call_counter(actuator_base_url, external)
        report["resource"]["status"] = "MEASURED"
        report["resource"]["samples"].append(
            {"offsetSeconds": 0.0, "cpuRatio": cpu, "memoryRatio": memory}
        )
        report["resource"]["peakCpuRatio"] = cpu
        report["resource"]["peakMemoryRatio"] = memory
        initial_stop = evaluate_resource_stop(
            cpu,
            memory,
            0.0,
            stops,
            resource_stop_state,
        )
        if initial_stop is not None:
            set_threshold_stop(initial_stop)
            return finish_report()

        sampler = lambda: actuator_resource_sample(actuator_base_url, actuator)
        enabled_layers = [layer for layer in ("L1", "L2", "L3") if profile["layers"][layer]]
        lowest_concurrency = load["concurrency"][0]
        baselines: dict[str, float | None] = {}
        l2_partitions: list[list[int]] = []
        if profile["layers"]["L2"]:
            document_ids = parse_document_id_pool(environment)
            if document_ids is None:
                raise RuntimeEvidenceError("unique disposable document pool is unavailable")
            weights = [lowest_concurrency * load["warmupSeconds"]] + [
                concurrency * load["steadyStateSeconds"]
                for concurrency in load["concurrency"]
            ]
            l2_partitions = partition_unique_document_ids(document_ids, weights)

        def invocation_environment(layer: str) -> dict[str, str]:
            child = dict(environment)
            if layer == "L2":
                if not l2_partitions:
                    raise RuntimeEvidenceError("unique disposable document pool was exhausted before invocation")
                child["RAG_PERFORMANCE_DOCUMENT_ID_POOL"] = json.dumps(
                    l2_partitions.pop(0), separators=(",", ":")
                )
            return child

        def invoke_jmeter(
            arguments: Sequence[str],
            child_environment: Mapping[str, str],
        ) -> InvocationResult:
            require_runtime_artifacts_stable()
            try:
                return run_jmeter_invocation(
                    jmeter,
                    arguments,
                    run_dir,
                    sampler,
                    actuator["sampleIntervalSeconds"],
                    stops,
                    report["resource"],
                    child_environment,
                    resource_stop_state,
                    run_started_monotonic,
                )
            finally:
                require_runtime_artifacts_stable()

        for layer in enabled_layers:
            external_before = external_call_counter(actuator_base_url, external)
            warmup_jtl = run_dir / f"warmup-{layer}.jtl"
            warmup_args = jmeter_arguments(
                jmx_snapshot,
                warmup_jtl,
                base_url,
                layer,
                lowest_concurrency,
                load["warmupSeconds"],
                load["rampSeconds"],
                load["ingestPollIntervalMs"],
                load["ingestPollTimeoutSeconds"],
            )
            invocation = invoke_jmeter(
                warmup_args,
                invocation_environment(layer),
            )
            external_after = external_call_counter(actuator_base_url, external)
            if external_after != external_before:
                raise RuntimeEvidenceError("external provider counter changed during zero-budget warm-up")
            if invocation.stop_reason is not None:
                set_threshold_stop(invocation.stop_reason)
                return finish_report()
            warmup_samples = read_jtl(warmup_jtl)
            urgent_stop = immediate_jtl_stop_reason(warmup_samples, stops)
            if urgent_stop is not None:
                set_threshold_stop(urgent_stop)
                return finish_report()
            warmup_primary = [sample for sample in warmup_samples if sample.label == PRIMARY_LABELS[layer]]
            if not warmup_primary:
                raise RuntimeEvidenceError("warm-up produced no primary samples")
            if layer == "L2":
                if any(sample.response_code == "597" for sample in warmup_samples):
                    raise RuntimeEvidenceError("L2 warm-up reused an existing ingest job")
                if any(sample.response_code == "598" for sample in warmup_samples):
                    raise RuntimeEvidenceError("unique disposable document pool exhausted during warm-up")
            require_window_evidence(
                warmup_primary,
                load["warmupSeconds"],
                lowest_concurrency,
                invocation.duration_seconds,
                "warm-up",
            )
            warmup_result = aggregate_samples(
                warmup_primary,
                layer,
                PRIMARY_LABELS[layer],
                lowest_concurrency,
                invocation.duration_seconds,
            )
            warmup_stop = evaluate_window_stop(
                warmup_result,
                warmup_samples,
                warmup_primary,
                stops,
                baseline_p99_ms=None,
                analysis_window_seconds=load["analysisWindowSeconds"],
            )
            if warmup_stop is not None:
                set_threshold_stop(warmup_stop)
                return finish_report()
            for concurrency in load["concurrency"]:
                external_before = external_call_counter(actuator_base_url, external)
                jtl = run_dir / f"stable-{layer}-c{concurrency}.jtl"
                arguments = jmeter_arguments(
                    jmx_snapshot,
                    jtl,
                    base_url,
                    layer,
                    concurrency,
                    load["steadyStateSeconds"],
                    load["rampSeconds"],
                    load["ingestPollIntervalMs"],
                    load["ingestPollTimeoutSeconds"],
                )
                invocation = invoke_jmeter(
                    arguments,
                    invocation_environment(layer),
                )
                external_after = external_call_counter(actuator_base_url, external)
                if external_after != external_before:
                    raise RuntimeEvidenceError("external provider counter changed during zero-budget window")
                if invocation.stop_reason is not None:
                    set_threshold_stop(invocation.stop_reason)
                    return finish_report()

                result, sampler_metrics, samples = aggregate_jtl(
                    jtl,
                    layer,
                    concurrency,
                    invocation.duration_seconds,
                )
                urgent_stop = immediate_jtl_stop_reason(samples, stops)
                if urgent_stop is not None:
                    set_threshold_stop(urgent_stop)
                    return finish_report()
                primary_samples = [sample for sample in samples if sample.label == PRIMARY_LABELS[layer]]
                if not primary_samples:
                    raise RuntimeEvidenceError("steady window produced no primary samples")
                if layer == "L2":
                    if any(sample.response_code == "597" for sample in samples):
                        raise RuntimeEvidenceError("L2 submission reused an existing ingest job")
                    if any(sample.response_code == "598" for sample in samples):
                        raise RuntimeEvidenceError("unique disposable document pool exhausted during steady window")
                require_window_evidence(
                    primary_samples,
                    load["steadyStateSeconds"],
                    concurrency,
                    invocation.duration_seconds,
                    "steady window",
                )
                report["results"].append(result)
                report["samplerMetrics"].extend(sampler_metrics)
                baseline = baselines.get(layer)
                stop = evaluate_window_stop(
                    result,
                    samples,
                    primary_samples,
                    stops,
                    baseline,
                    load["analysisWindowSeconds"],
                )
                if concurrency == lowest_concurrency:
                    baselines[layer] = result["p99Ms"]
                if stop is not None:
                    set_threshold_stop(stop)
                    return finish_report()

        report["runStatus"] = "COMPLETED"
        report["runtimeEvidenceStatus"] = "VALID"
        report["thresholdStatus"] = "WITHIN_PROFILE"
        report["stopReason"] = None
        return finish_report()
    except RuntimeEvidenceError:
        if report["resource"]["status"] == "UNKNOWN":
            report["resource"]["status"] = "FAILED"
            report["resource"]["failureReason"] = "Required Actuator runtime evidence was unavailable"
        report["runStatus"] = "FAILED"
        report["runtimeEvidenceStatus"] = "INVALID"
        report["thresholdStatus"] = "INVALID"
        report["stopReason"] = {"kind": "RUNTIME_EVIDENCE", "detail": "required runtime evidence or JMeter result was unavailable"}
        return finish_report()


def validate_only(profile_path: Path, jmx_path: Path, schema_path: Path) -> dict[str, Any]:
    profile = load_json_object(profile_path)
    validate_profile(profile)
    jmx_summary = validate_jmx(jmx_path)
    schema_hash = validate_report_schema_contract(schema_path)
    return {
        "mode": "validate-only",
        "runStatus": "NOT_RUN",
        "evidenceLevel": "FIXTURE_STATIC",
        "valid": True,
        "httpRequestsSent": 0,
        "jmx": jmx_summary,
        "profileSha256": sha256_file(profile_path),
        "jmxSha256": sha256_file(jmx_path),
        "schemaSha256": schema_hash,
    }
