from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock


PERFORMANCE_DIR = Path(__file__).resolve().parents[1]
if str(PERFORMANCE_DIR) not in sys.path:
    sys.path.insert(0, str(PERFORMANCE_DIR))

import performance_workflow as workflow


PROFILE_PATH = PERFORMANCE_DIR / "performance-profile.example.json"
JMX_PATH = PERFORMANCE_DIR / "rag-layered-performance.jmx"
SCHEMA_PATH = PERFORMANCE_DIR / "performance-report-v1.schema.json"


def example_profile() -> dict:
    return json.loads(PROFILE_PATH.read_text(encoding="utf-8"))


def executable_profile() -> dict:
    profile = example_profile()
    profile["execution"]["enabled"] = True
    profile["execution"]["disposableStateConfirmed"] = True
    profile["external"]["zeroExternalCallMetricPath"] = "/actuator/metrics/rag.external.calls"
    profile["workload"].update(
        {
            "fixtureFingerprintSha256": "e" * 64,
            "runtimeBuildFingerprintSha256": "f" * 64,
            "corpusDocumentCount": 10,
            "corpusChunkCount": 40,
            "ingestFileSizeBytes": 1024,
            "ingestPageCount": 2,
        }
    )
    return profile


def provenance() -> dict:
    return {
        "gitHead": "a" * 40,
        "gitDirty": True,
        "gitDirtyFingerprint": "b" * 64,
        "gitEndHead": "a" * 40,
        "gitEndDirty": True,
        "gitEndDirtyFingerprint": "b" * 64,
        "sourceDriftStatus": "STABLE",
        "profileSha256": "c" * 64,
        "jmxSha256": "d" * 64,
        "schemaSha256": workflow.EXPECTED_REPORT_SCHEMA_SHA256,
        "fixtureFingerprintSha256": "e" * 64,
        "runtimeBuildFingerprintSha256": "f" * 64,
        "runtimeIdentityStatus": "NOT_CHECKED",
        "jmeterVersion": "5.6.3",
        "jmeterExecutableSha256": "1" * 64,
        "environment": {"operatingSystem": "test"},
    }


def metric(concurrency: int = 1) -> dict:
    return {
        "layer": "L1",
        "stage": "L1-retrieval",
        "concurrency": concurrency,
        "sampleCount": concurrency,
        "measurementDurationSeconds": 180.0,
        "observedSampleSpanSeconds": 180.0,
        "participatingThreads": concurrency,
        "p50Ms": 10.0,
        "p95Ms": 20.0,
        "p99Ms": 30.0,
        "percentileMethod": "nearest-rank",
        "throughputPerSecond": 1.0,
        "errorRate": 0.0,
        "http429Count": 0,
        "http5xxCount": 0,
    }


def completed_report() -> dict:
    report = workflow.base_report(provenance())
    report["runStatus"] = "COMPLETED"
    report["runtimeEvidenceStatus"] = "VALID"
    report["thresholdStatus"] = "WITHIN_PROFILE"
    report["stopReason"] = None
    report["results"] = [metric()]
    report["samplerMetrics"] = [metric()]
    report["resource"] = {
        "status": "MEASURED",
        "samples": [{"offsetSeconds": 0.0, "cpuRatio": 0.1, "memoryRatio": 0.2}],
        "peakCpuRatio": 0.1,
        "peakMemoryRatio": 0.2,
        "failureReason": None,
    }
    report["provenance"]["runtimeIdentityStatus"] = "MATCHED"
    return report


class ProfileGateTests(unittest.TestCase):
    def test_example_profile_is_safe_and_valid(self) -> None:
        profile = example_profile()
        workflow.validate_profile(profile)
        self.assertFalse(profile["execution"]["enabled"])
        self.assertFalse(profile["execution"]["disposableStateConfirmed"])
        self.assertEqual([1, 2, 4, 8], profile["load"]["concurrency"])
        self.assertEqual(0, profile["load"]["rampSeconds"])
        self.assertIsNone(profile["workload"]["fixtureFingerprintSha256"])
        self.assertEqual("disabled", profile["external"]["mode"])
        self.assertEqual(0, profile["external"]["costBudgetUsd"])
        self.assertIsNone(profile["external"]["pricingVersion"])
        self.assertEqual(120, profile["stopConditions"]["cpuConsecutiveSeconds"])

    def test_profile_rejects_invalid_concurrency_and_threshold(self) -> None:
        profile = example_profile()
        profile["load"]["concurrency"] = [2, 1, 1]
        profile["stopConditions"]["errorRateGreaterThan"] = 1.5
        errors = workflow.profile_errors(profile)
        self.assertTrue(any("concurrency" in error for error in errors))
        self.assertTrue(any("errorRate" in error for error in errors))
        profile = example_profile()
        profile["stopConditions"]["cpuConsecutiveSeconds"] = 0
        self.assertTrue(any("CPU consecutive" in error for error in workflow.profile_errors(profile)))
        profile = example_profile()
        profile["stopConditions"]["stopOnAny429"] = False
        self.assertTrue(any("stopOnAny429" in error for error in workflow.profile_errors(profile)))

    def test_profile_rejects_payload_or_business_id_fields(self) -> None:
        profile = example_profile()
        profile["query"] = "must-not-be-saved"
        self.assertTrue(any("must not contain" in error for error in workflow.profile_errors(profile)))

    def test_l4_is_always_rejected(self) -> None:
        profile = executable_profile()
        profile["layers"]["L4"] = True
        with self.assertRaises(workflow.WorkflowError):
            workflow.validate_profile(profile)
        errors = workflow.execution_gate_errors(profile, explicit_execute=True)
        self.assertTrue(any("L4" in error for error in errors))

    def test_execution_requires_cli_and_profile_double_gate(self) -> None:
        safe_profile = example_profile()
        errors = workflow.execution_gate_errors(safe_profile, explicit_execute=False)
        self.assertTrue(any("--execute" in error for error in errors))
        self.assertTrue(any("execution.enabled" in error for error in errors))

        enabled = executable_profile()
        errors = workflow.execution_gate_errors(enabled, explicit_execute=False)
        self.assertEqual(["explicit --execute is required"], errors)
        workflow.validate_execution_gate(enabled, explicit_execute=True)

    def test_execution_requires_loopback_and_disposable_state(self) -> None:
        profile = executable_profile()
        profile["baseUrl"] = "https://example.com"
        profile["execution"]["disposableStateConfirmed"] = False
        errors = workflow.execution_gate_errors(profile, explicit_execute=True)
        self.assertTrue(any("loopback" in error for error in errors))
        self.assertTrue(any("disposableStateConfirmed" in error for error in errors))
        self.assertFalse(workflow.is_loopback_base_url("http://127.0.0.1.evil.invalid:8080"))
        self.assertFalse(workflow.is_loopback_base_url("http://0.0.0.0:8080"))
        self.assertTrue(workflow.is_loopback_base_url("http://[::1]:8080"))

    def test_execution_requires_safe_workload_scale_and_fingerprints(self) -> None:
        profile = executable_profile()
        profile["workload"]["fixtureFingerprintSha256"] = None
        profile["workload"]["corpusChunkCount"] = None
        errors = workflow.execution_gate_errors(profile, explicit_execute=True)
        self.assertTrue(any("fixtureFingerprint" in error for error in errors))
        self.assertTrue(any("corpusChunkCount" in error for error in errors))

    def test_actuator_origin_is_separate_and_must_be_loopback(self) -> None:
        profile = example_profile()
        self.assertEqual("http://127.0.0.1:8081", profile["actuator"]["baseUrl"])
        profile["actuator"]["baseUrl"] = "https://metrics.example.com"
        errors = workflow.profile_errors(profile)
        self.assertTrue(any("actuator.baseUrl" in error for error in errors))

    def test_external_budget_pair_is_fail_closed(self) -> None:
        profile = example_profile()
        profile["external"]["costBudgetUsd"] = 1
        self.assertTrue(workflow.external_policy_errors(profile["external"]))

    def test_zero_budget_execution_requires_measurable_zero_external_calls(self) -> None:
        profile = executable_profile()
        profile["external"]["zeroExternalCallMetricPath"] = None
        errors = workflow.execution_gate_errors(profile, explicit_execute=True)
        self.assertTrue(any("zero-external-call" in error for error in errors))
        profile["external"] = {
            "mode": "provider-budgeted",
            "costBudgetUsd": 1,
            "pricingVersion": "v1",
            "zeroExternalCallMetricPath": None,
        }
        errors = workflow.execution_gate_errors(profile, explicit_execute=True)
        self.assertTrue(any("UNKNOWN" in error for error in errors))
        profile["external"] = {
            "mode": "provider-budgeted",
            "costBudgetUsd": 1,
            "pricingVersion": None,
        }
        self.assertTrue(workflow.external_policy_errors(profile["external"]))

    def test_runtime_values_are_environment_only_and_shape_checked(self) -> None:
        profile = executable_profile()
        environment = {
            "RAG_PERFORMANCE_TOKEN": "secret",
            "RAG_PERFORMANCE_L1_BODY": json.dumps({"courseId": 1, "query": "fixed", "topK": 5}),
            "RAG_PERFORMANCE_DOCUMENT_ID_POOL": json.dumps(list(range(1, 33))),
            "RAG_PERFORMANCE_L3_BODY": json.dumps({"courseId": 1, "question": "fixed"}),
        }
        self.assertEqual([], workflow.runtime_environment_errors(profile, environment))
        environment["RAG_PERFORMANCE_L1_BODY"] = json.dumps({"courseId": 1, "query": " ", "topK": 5})
        self.assertTrue(any("query" in error for error in workflow.runtime_environment_errors(profile, environment)))

    def test_runtime_fixture_fingerprint_binds_payloads_and_scale_without_persisting_them(self) -> None:
        profile = executable_profile()
        environment = {
            "RAG_PERFORMANCE_TOKEN": "secret",
            "RAG_PERFORMANCE_L1_BODY": json.dumps(
                {"courseId": 1, "query": "private fixed query", "topK": 5}
            ),
            "RAG_PERFORMANCE_DOCUMENT_ID_POOL": json.dumps(list(range(1, 33))),
            "RAG_PERFORMANCE_L3_BODY": json.dumps(
                {"courseId": 1, "question": "private fixed question"}
            ),
        }
        fingerprint = workflow.runtime_fixture_fingerprint(profile, environment)
        self.assertRegex(fingerprint, r"^[0-9a-f]{64}$")
        profile["workload"]["fixtureFingerprintSha256"] = fingerprint
        self.assertEqual(
            fingerprint,
            workflow.verify_runtime_fixture_fingerprint(profile, environment),
        )
        changed = dict(environment)
        changed["RAG_PERFORMANCE_L1_BODY"] = json.dumps(
            {"courseId": 1, "query": "changed", "topK": 5}
        )
        with self.assertRaisesRegex(workflow.WorkflowError, "does not match"):
            workflow.verify_runtime_fixture_fingerprint(profile, changed)

    def test_l3_requires_a_fresh_session_per_request(self) -> None:
        profile = executable_profile()
        profile["layers"].update({"L1": False, "L2": False, "L3": True, "L4": False})
        environment = {
            "RAG_PERFORMANCE_L3_BODY": json.dumps({"courseId": 1, "question": "fixed"}),
        }
        self.assertEqual([], workflow.runtime_environment_errors(profile, environment))
        environment["RAG_PERFORMANCE_L3_BODY"] = json.dumps(
            {"courseId": 1, "question": "fixed", "sessionId": None}
        )
        self.assertEqual([], workflow.runtime_environment_errors(profile, environment))
        environment["RAG_PERFORMANCE_L3_BODY"] = json.dumps(
            {"courseId": 1, "question": "fixed", "sessionId": 99}
        )
        self.assertTrue(
            any("sessionId" in error for error in workflow.runtime_environment_errors(profile, environment))
        )

    def test_l2_rejects_duplicate_or_undersized_document_pool(self) -> None:
        profile = executable_profile()
        environment = {
            "RAG_PERFORMANCE_TOKEN": "secret",
            "RAG_PERFORMANCE_L1_BODY": json.dumps({"courseId": 1, "query": "fixed", "topK": 5}),
            "RAG_PERFORMANCE_DOCUMENT_ID_POOL": "[1,1,2]",
            "RAG_PERFORMANCE_L3_BODY": json.dumps({"courseId": 1, "question": "fixed"}),
        }
        errors = workflow.runtime_environment_errors(profile, environment)
        self.assertTrue(any("unique" in error for error in errors))
        environment["RAG_PERFORMANCE_DOCUMENT_ID_POOL"] = json.dumps(list(range(1, 10)))
        errors = workflow.runtime_environment_errors(profile, environment)
        self.assertTrue(any("too small" in error for error in errors))

    def test_document_pool_partition_is_disjoint_and_complete(self) -> None:
        document_ids = list(range(1, 101))
        partitions = workflow.partition_unique_document_ids(document_ids, [60, 180, 360, 720, 1440])
        flattened = [item for partition in partitions for item in partition]
        self.assertEqual(document_ids, flattened)
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertTrue(all(partition for partition in partitions))


class MetricsTests(unittest.TestCase):
    def test_nearest_rank_percentiles(self) -> None:
        values = [4, 1, 3, 2]
        self.assertEqual(2, workflow.nearest_rank(values, 0.50))
        self.assertEqual(4, workflow.nearest_rank(values, 0.95))
        self.assertEqual(4, workflow.nearest_rank(values, 0.99))
        self.assertIsNone(workflow.nearest_rank([], 0.99))

    def test_error_throughput_and_status_aggregation(self) -> None:
        samples = [
            workflow.Sample(1000, 100, "L1-retrieval", "200", True),
            workflow.Sample(1100, 200, "L1-retrieval", "429", False),
            workflow.Sample(1200, 100, "L1-retrieval", "503", False),
            workflow.Sample(1300, 100, "L1-retrieval", "200", True),
        ]
        metric = workflow.aggregate_samples(samples, "L1", "L1-retrieval", 1)
        self.assertEqual(4, metric["sampleCount"])
        self.assertEqual(0.5, metric["errorRate"])
        self.assertEqual(1, metric["http429Count"])
        self.assertEqual(1, metric["http5xxCount"])
        self.assertAlmostEqual(10.0, metric["throughputPerSecond"])
        self.assertEqual("nearest-rank", metric["percentileMethod"])

    def test_any_429_stops_even_when_primary_sampler_is_clean(self) -> None:
        primary = [workflow.Sample(0, 10, "L2-ingest-terminal", "200", True)]
        all_samples = primary + [workflow.Sample(1, 5, "L2-ingest-poll", "429", False)]
        result = workflow.aggregate_samples(primary, "L2", "L2-ingest-terminal", 1)
        stop = workflow.evaluate_window_stop(
            result,
            all_samples,
            primary,
            example_profile()["stopConditions"],
            baseline_p99_ms=None,
            analysis_window_seconds=30,
        )
        self.assertEqual("HTTP_429", stop["kind"])

    def test_p99_requires_consecutive_windows(self) -> None:
        samples = [
            workflow.Sample(0, 30, "L1-retrieval", "200", True),
            workflow.Sample(30_000, 31, "L1-retrieval", "200", True),
            workflow.Sample(60_000, 1, "L1-retrieval", "200", True),
        ]
        self.assertTrue(workflow.consecutive_window_p99_breach(samples, 10, 2.0, 2, 30))
        one_breach = [
            workflow.Sample(0, 30, "L1-retrieval", "200", True),
            workflow.Sample(30_000, 19, "L1-retrieval", "200", True),
            workflow.Sample(60_000, 1, "L1-retrieval", "200", True),
        ]
        self.assertFalse(workflow.consecutive_window_p99_breach(one_breach, 10, 2.0, 2, 30))

        missing_middle_window = [
            workflow.Sample(0, 30, "L1-retrieval", "200", True),
            workflow.Sample(60_000, 31, "L1-retrieval", "200", True),
            workflow.Sample(90_000, 1, "L1-retrieval", "200", True),
        ]
        self.assertFalse(
            workflow.consecutive_window_p99_breach(
                missing_middle_window, 10, 2.0, 2, 30
            )
        )

        incomplete_last_window = [
            workflow.Sample(0, 30, "L1-retrieval", "200", True),
            workflow.Sample(30_000, 31, "L1-retrieval", "200", True),
        ]
        self.assertFalse(
            workflow.consecutive_window_p99_breach(
                incomplete_last_window, 10, 2.0, 2, 30
            )
        )

    def test_cpu_and_memory_thresholds_are_strict_greater_than(self) -> None:
        stops = example_profile()["stopConditions"]
        state = workflow.ResourceStopState()
        self.assertIsNone(workflow.evaluate_resource_stop(0.85, 0.80, 0.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.851, 0.1, 0.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.851, 0.1, 119.999, stops, state))
        self.assertEqual(
            "CPU", workflow.evaluate_resource_stop(0.851, 0.1, 120.0, stops, state)["kind"]
        )
        self.assertEqual(
            "MEMORY",
            workflow.evaluate_resource_stop(
                0.1, 0.801, 0.0, stops, workflow.ResourceStopState()
            )["kind"],
        )

    def test_cpu_consecutive_duration_resets_at_threshold(self) -> None:
        stops = example_profile()["stopConditions"]
        state = workflow.ResourceStopState()
        self.assertIsNone(workflow.evaluate_resource_stop(0.9, 0.1, 0.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.9, 0.1, 60.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.85, 0.1, 120.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.9, 0.1, 180.0, stops, state))
        self.assertIsNone(workflow.evaluate_resource_stop(0.9, 0.1, 299.999, stops, state))
        self.assertEqual(
            "CPU", workflow.evaluate_resource_stop(0.9, 0.1, 300.0, stops, state)["kind"]
        )

    def test_cpu_consecutive_duration_does_not_bridge_an_unsampled_gap(self) -> None:
        stops = example_profile()["stopConditions"]
        state = workflow.ResourceStopState()
        self.assertIsNone(
            workflow.evaluate_resource_stop(0.9, 0.1, 0.0, stops, state, 7.5)
        )
        self.assertIsNone(
            workflow.evaluate_resource_stop(0.9, 0.1, 60.0, stops, state, 7.5)
        )
        self.assertIsNone(
            workflow.evaluate_resource_stop(0.9, 0.1, 180.0, stops, state, 7.5)
        )
        self.assertIsNone(
            workflow.evaluate_resource_stop(0.9, 0.1, 299.0, stops, state, 7.5)
        )

    def test_csv_jtl_aggregation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.jtl"
            path.write_text(
                "timeStamp,elapsed,label,responseCode,success,threadName\n"
                "1000,10,L3-chat,200,true,Selected layer load 1-1\n"
                "1010,20,L3-chat,500,false,Selected layer load 1-2\n",
                encoding="utf-8",
            )
            result, stages, samples = workflow.aggregate_jtl(path, "L3", 2)
            self.assertEqual(2, result["sampleCount"])
            self.assertEqual(1, result["http5xxCount"])
            self.assertEqual(1, len(stages))
            self.assertEqual(2, len(samples))
            self.assertEqual(2, result["participatingThreads"])

    def test_all_layers_require_near_full_window_coverage(self) -> None:
        short = [workflow.Sample(0, 1000, "L1-retrieval", "200", True)]
        with self.assertRaises(workflow.RuntimeEvidenceError):
            workflow.require_window_coverage(short, 180, "steady window")
        complete = [
            workflow.Sample(0, 1000, "L3-chat", "200", True),
            workflow.Sample(179_000, 1000, "L3-chat", "200", True),
        ]
        workflow.require_window_coverage(complete, 180, "steady window")

    def test_window_evidence_requires_real_duration_and_every_thread(self) -> None:
        complete = [
            workflow.Sample(
                offset,
                1000,
                "L1-retrieval",
                "200",
                True,
                thread,
            )
            for thread in ("Selected layer load 1-1", "Selected layer load 1-2")
            for offset in range(0, 180_000, 10_000)
        ]
        workflow.require_window_evidence(complete, 180, 2, 180.0, "steady window")
        with self.assertRaisesRegex(workflow.RuntimeEvidenceError, "invocation ended early"):
            workflow.require_window_evidence(complete, 180, 2, 10.0, "steady window")
        sparse = [
            workflow.Sample(0, 1000, "L1-retrieval", "200", True, "thread-1"),
            workflow.Sample(179_000, 1000, "L1-retrieval", "200", True, "thread-2"),
        ]
        with self.assertRaisesRegex(
            workflow.RuntimeEvidenceError,
            "joined too late|did not remain active|inactive thread gap",
        ):
            workflow.require_window_evidence(sparse, 180, 2, 180.0, "steady window")
        one_thread = [
            workflow.Sample(
                sample.timestamp_ms,
                sample.elapsed_ms,
                sample.label,
                sample.response_code,
                sample.success,
                "Selected layer load 1-1",
            )
            for sample in complete
        ]
        with self.assertRaisesRegex(workflow.RuntimeEvidenceError, "every configured"):
            workflow.require_window_evidence(one_thread, 180, 2, 180.0, "steady window")


class EvidenceAndOutputTests(unittest.TestCase):
    def test_report_schema_versions_required_status_and_evidence_fields(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual(1, schema["properties"]["schemaVersion"]["const"])
        self.assertEqual(
            ["NOT_RUN", "COMPLETED", "STOPPED", "FAILED"],
            schema["properties"]["runStatus"]["enum"],
        )
        self.assertIn("providerUsage", schema["required"])
        self.assertIn("samplerMetrics", schema["required"])
        self.assertIn("internalStageMetrics", schema["required"])
        self.assertEqual(
            ["NOT_RUN", "VALID", "INVALID"],
            schema["properties"]["runtimeEvidenceStatus"]["enum"],
        )

    def test_unknown_provider_usage_propagates_null(self) -> None:
        report = workflow.base_report(provenance())
        workflow.validate_report_shape(report)
        usage = report["providerUsage"]
        self.assertEqual("UNKNOWN", usage["status"])
        self.assertIsNone(usage["costUsd"])
        self.assertIsNone(usage["pricingVersion"])
        self.assertIsNone(usage["inputTokens"])

        invalid = copy.deepcopy(report)
        invalid["providerUsage"]["costUsd"] = 0
        with self.assertRaises(workflow.WorkflowError):
            workflow.validate_report_shape(invalid)

    def test_report_rejects_impossible_runtime_status_combinations(self) -> None:
        valid = completed_report()
        workflow.validate_report_shape(valid)

        stopped_without_reason = copy.deepcopy(valid)
        stopped_without_reason["runStatus"] = "STOPPED"
        stopped_without_reason["thresholdStatus"] = "STOPPED_BY_THRESHOLD"
        with self.assertRaisesRegex(workflow.WorkflowError, "threshold stop reason"):
            workflow.validate_report_shape(stopped_without_reason)

        for field in ("results", "samplerMetrics"):
            empty_completed = copy.deepcopy(valid)
            empty_completed[field] = []
            with self.assertRaisesRegex(workflow.WorkflowError, "result collections"):
                workflow.validate_report_shape(empty_completed)

        missing_resource = copy.deepcopy(valid)
        missing_resource["resource"] = {
            "status": "UNKNOWN",
            "samples": [],
            "peakCpuRatio": None,
            "peakMemoryRatio": None,
            "failureReason": None,
        }
        with self.assertRaisesRegex(workflow.WorkflowError, "measured resources"):
            workflow.validate_report_shape(missing_resource)

        zero_samples = copy.deepcopy(valid)
        zero_samples["results"][0]["sampleCount"] = 0
        with self.assertRaisesRegex(workflow.WorkflowError, "at least one sample"):
            workflow.validate_report_shape(zero_samples)

        missing_thread = copy.deepcopy(valid)
        missing_thread["results"][0]["concurrency"] = 2
        with self.assertRaisesRegex(workflow.WorkflowError, "every configured"):
            workflow.validate_report_shape(missing_thread)

    def test_run_directory_and_report_are_exclusive_create(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "runs"
            run = workflow.create_run_directory(root, "fixed-run")
            with self.assertRaises(FileExistsError):
                workflow.create_run_directory(root, "fixed-run")
            report = workflow.base_report(provenance())
            workflow.write_report_exclusive(run, report)
            with self.assertRaises(FileExistsError):
                workflow.write_report_exclusive(run, report)

    def test_report_refuses_sensitive_field_and_contains_no_supplied_secret(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary)
            report = workflow.base_report(provenance())
            serialized = json.dumps(report, sort_keys=True)
            self.assertNotIn("super-secret-token", serialized)
            self.assertNotIn("private fixed query", serialized)
            report["query"] = "private fixed query"
            with self.assertRaises(workflow.WorkflowError):
                workflow.write_report_exclusive(run, report)

    def test_missing_actuator_is_a_runtime_evidence_failure(self) -> None:
        with mock.patch.object(workflow, "_http_json", side_effect=workflow.RuntimeEvidenceError("missing")):
            with self.assertRaises(workflow.RuntimeEvidenceError):
                workflow.actuator_preflight("http://127.0.0.1:8080", example_profile()["actuator"])

    def test_external_counter_accepts_standard_micrometer_count(self) -> None:
        with mock.patch.object(
            workflow,
            "_http_json",
            return_value={"measurements": [{"statistic": "COUNT", "value": 7.0}]},
        ):
            self.assertEqual(
                7.0,
                workflow.external_call_counter(
                    "http://127.0.0.1:8081",
                    {"zeroExternalCallMetricPath": "/actuator/metrics/rag.external.calls"},
                ),
            )

    def test_runtime_build_identity_is_read_live_from_actuator_info(self) -> None:
        actuator = example_profile()["actuator"]
        with mock.patch.object(
            workflow,
            "_http_json",
            return_value={
                "rag": {
                    "performance": {
                        "runtimeBuildFingerprintSha256": "f" * 64
                    }
                }
            },
        ) as http:
            self.assertEqual(
                "f" * 64,
                workflow.actuator_runtime_build_fingerprint(
                    "http://127.0.0.1:8081", actuator, "f" * 64
                ),
            )
        http.assert_called_once_with(
            "http://127.0.0.1:8081", "/actuator/info"
        )
        with mock.patch.object(
            workflow,
            "_http_json",
            return_value={
                "rag": {
                    "performance": {
                        "runtimeBuildFingerprintSha256": "e" * 64
                    }
                }
            },
        ):
            with self.assertRaises(workflow.RuntimeEvidenceError):
                workflow.actuator_runtime_build_fingerprint(
                    "http://127.0.0.1:8081", actuator, "f" * 64
                )

    def test_report_schema_is_pinned_against_weakening(self) -> None:
        self.assertEqual(
            workflow.EXPECTED_REPORT_SCHEMA_SHA256,
            workflow.validate_report_schema_contract(SCHEMA_PATH),
        )
        with tempfile.TemporaryDirectory() as temporary:
            weakened = Path(temporary) / "schema.json"
            text = SCHEMA_PATH.read_text(encoding="utf-8").replace(
                '"additionalProperties": false',
                '"additionalProperties": true',
                1,
            )
            weakened.write_text(text, encoding="utf-8")
            with self.assertRaisesRegex(workflow.WorkflowError, "pinned"):
                workflow.validate_report_schema_contract(weakened)

    def test_git_dirty_fingerprint_includes_staged_blob_content(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", *arguments],
                    cwd=repo,
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

            git("init")
            tracked = repo / "tracked.txt"
            tracked.write_text("initial\n", encoding="utf-8")
            git("add", "tracked.txt")
            git(
                "-c",
                "user.name=Step44 Test",
                "-c",
                "user.email=user@example.com",
                "commit",
                "-m",
                "initial",
            )
            tracked.write_text("staged-one\n", encoding="utf-8")
            git("add", "tracked.txt")
            first = workflow.git_snapshot(repo)
            tracked.write_text("staged-two\n", encoding="utf-8")
            git("add", "tracked.txt")
            second = workflow.git_snapshot(repo)
            self.assertNotEqual(
                first["gitDirtyFingerprint"], second["gitDirtyFingerprint"]
            )

    def test_validate_only_does_not_call_http_or_create_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "runs"
            with mock.patch.object(workflow, "_http_json", side_effect=AssertionError("HTTP must not run")):
                summary = workflow.validate_only(PROFILE_PATH, JMX_PATH, SCHEMA_PATH)
            self.assertEqual("NOT_RUN", summary["runStatus"])
            self.assertEqual("FIXTURE_STATIC", summary["evidenceLevel"])
            self.assertEqual(0, summary["httpRequestsSent"])
            self.assertFalse(output.exists())

    def test_execute_function_cannot_bypass_explicit_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(executable_profile()), encoding="utf-8")
            with mock.patch.object(workflow, "_http_json", side_effect=AssertionError("HTTP must not run")):
                with self.assertRaisesRegex(workflow.WorkflowError, "--execute"):
                    workflow.execute_workflow(
                        repo_root=PERFORMANCE_DIR.parents[2],
                        profile_path=profile_path,
                        jmx_path=JMX_PATH,
                        schema_path=SCHEMA_PATH,
                        output_root=root / "runs",
                        jmeter_path=None,
                        environ={},
                    )

    def test_execution_scrapes_separate_actuator_loopback_origin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = executable_profile()
            profile["layers"].update({"L1": True, "L2": False, "L3": False, "L4": False})
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            environment = {
                "RAG_PERFORMANCE_TOKEN": "secret",
                "RAG_PERFORMANCE_L1_BODY": json.dumps(
                    {"courseId": 1, "query": "fixed", "topK": 5}
                ),
            }
            profile["workload"]["fixtureFingerprintSha256"] = (
                workflow.runtime_fixture_fingerprint(profile, environment)
            )
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            fake_jmeter = root / "jmeter.bat"
            fake_jmeter.write_text("@echo off\n", encoding="utf-8")
            with (
                mock.patch.object(workflow, "discover_jmeter", return_value=fake_jmeter),
                mock.patch.object(workflow, "jmeter_version", return_value="5.6.3"),
                mock.patch.object(workflow, "git_snapshot", return_value={
                    "gitHead": "a" * 40,
                    "gitDirty": False,
                    "gitDirtyFingerprint": "b" * 64,
                }),
                mock.patch.object(workflow, "actuator_preflight", return_value=(0.1, 0.81)) as preflight,
                mock.patch.object(
                    workflow,
                    "actuator_runtime_build_fingerprint",
                    return_value="f" * 64,
                ) as identity,
                mock.patch.object(workflow, "external_call_counter", return_value=0.0) as counter,
            ):
                report_path = workflow.execute_workflow(
                    repo_root=PERFORMANCE_DIR.parents[2],
                    profile_path=profile_path,
                    jmx_path=JMX_PATH,
                    schema_path=SCHEMA_PATH,
                    output_root=root / "runs",
                    jmeter_path=None,
                    environ=environment,
                    explicit_execute=True,
                )
            preflight.assert_called_once_with(
                "http://127.0.0.1:8081", profile["actuator"]
            )
            self.assertEqual(2, identity.call_count)
            counter.assert_called_once_with(
                "http://127.0.0.1:8081", profile["external"]
            )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("STOPPED", report["runStatus"])
            self.assertEqual("VALID", report["runtimeEvidenceStatus"])
            self.assertEqual("STOPPED_BY_THRESHOLD", report["thresholdStatus"])
            self.assertEqual("MEMORY", report["stopReason"]["kind"])
            self.assertTrue(
                (report_path.parent / "performance-profile.snapshot.json").is_file()
            )
            self.assertTrue(
                (report_path.parent / "rag-layered-performance.snapshot.jmx").is_file()
            )
            self.assertTrue(
                (
                    report_path.parent
                    / "performance-report-v1.snapshot.schema.json"
                ).is_file()
            )
            self.assertEqual("STABLE", report["provenance"]["sourceDriftStatus"])

    def test_execution_marks_source_artifact_drift_invalid(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = executable_profile()
            profile["layers"].update(
                {"L1": True, "L2": False, "L3": False, "L4": False}
            )
            environment = {
                "RAG_PERFORMANCE_TOKEN": "secret",
                "RAG_PERFORMANCE_L1_BODY": json.dumps(
                    {"courseId": 1, "query": "fixed", "topK": 5}
                ),
            }
            profile["workload"]["fixtureFingerprintSha256"] = (
                workflow.runtime_fixture_fingerprint(profile, environment)
            )
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            fake_jmeter = root / "jmeter.bat"
            fake_jmeter.write_text("@echo off\n", encoding="utf-8")

            def drift_profile(*_args: object) -> tuple[float, float]:
                profile_path.write_text(
                    json.dumps(profile, indent=2) + "\n", encoding="utf-8"
                )
                return 0.1, 0.81

            stable_git = {
                "gitHead": "a" * 40,
                "gitDirty": False,
                "gitDirtyFingerprint": "b" * 64,
            }
            with (
                mock.patch.object(workflow, "discover_jmeter", return_value=fake_jmeter),
                mock.patch.object(workflow, "jmeter_version", return_value="5.6.3"),
                mock.patch.object(workflow, "git_snapshot", return_value=stable_git),
                mock.patch.object(workflow, "actuator_preflight", side_effect=drift_profile),
                mock.patch.object(
                    workflow,
                    "actuator_runtime_build_fingerprint",
                    return_value="f" * 64,
                ),
                mock.patch.object(workflow, "external_call_counter", return_value=0.0),
            ):
                report_path = workflow.execute_workflow(
                    repo_root=PERFORMANCE_DIR.parents[2],
                    profile_path=profile_path,
                    jmx_path=JMX_PATH,
                    schema_path=SCHEMA_PATH,
                    output_root=root / "runs",
                    jmeter_path=None,
                    environ=environment,
                    explicit_execute=True,
                )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("FAILED", report["runStatus"])
            self.assertEqual("INVALID", report["runtimeEvidenceStatus"])
            self.assertEqual("SOURCE_DRIFT", report["stopReason"]["kind"])
            self.assertEqual("DRIFTED", report["provenance"]["sourceDriftStatus"])

    def test_execution_marks_snapshot_drift_invalid_after_invocation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = executable_profile()
            profile["layers"].update(
                {"L1": True, "L2": False, "L3": False, "L4": False}
            )
            environment = {
                "RAG_PERFORMANCE_TOKEN": "secret",
                "RAG_PERFORMANCE_L1_BODY": json.dumps(
                    {"courseId": 1, "query": "fixed", "topK": 5}
                ),
            }
            profile["workload"]["fixtureFingerprintSha256"] = (
                workflow.runtime_fixture_fingerprint(profile, environment)
            )
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            fake_jmeter = root / "jmeter.bat"
            fake_jmeter.write_text("@echo off\n", encoding="utf-8")
            stable_git = {
                "gitHead": "a" * 40,
                "gitDirty": False,
                "gitDirtyFingerprint": "b" * 64,
            }

            def drift_snapshot(*arguments: object) -> workflow.InvocationResult:
                run_dir = arguments[2]
                assert isinstance(run_dir, Path)
                (run_dir / "rag-layered-performance.snapshot.jmx").write_text(
                    "tampered\n", encoding="utf-8"
                )
                return workflow.InvocationResult(stop_reason=None, duration_seconds=1.0)

            with (
                mock.patch.object(workflow, "discover_jmeter", return_value=fake_jmeter),
                mock.patch.object(workflow, "jmeter_version", return_value="5.6.3"),
                mock.patch.object(workflow, "git_snapshot", return_value=stable_git),
                mock.patch.object(workflow, "actuator_preflight", return_value=(0.1, 0.1)),
                mock.patch.object(
                    workflow,
                    "actuator_runtime_build_fingerprint",
                    return_value="f" * 64,
                ),
                mock.patch.object(workflow, "external_call_counter", return_value=0.0),
                mock.patch.object(
                    workflow, "run_jmeter_invocation", side_effect=drift_snapshot
                ),
            ):
                report_path = workflow.execute_workflow(
                    repo_root=PERFORMANCE_DIR.parents[2],
                    profile_path=profile_path,
                    jmx_path=JMX_PATH,
                    schema_path=SCHEMA_PATH,
                    output_root=root / "runs",
                    jmeter_path=None,
                    environ=environment,
                    explicit_execute=True,
                )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("FAILED", report["runStatus"])
            self.assertEqual("INVALID", report["runtimeEvidenceStatus"])
            self.assertEqual("SOURCE_DRIFT", report["stopReason"]["kind"])
            self.assertEqual("DRIFTED", report["provenance"]["sourceDriftStatus"])

    def test_execution_freezes_environment_before_runtime_callbacks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = executable_profile()
            profile["layers"].update(
                {"L1": True, "L2": False, "L3": False, "L4": False}
            )
            original_body = json.dumps(
                {"courseId": 1, "query": "fixed", "topK": 5}
            )
            environment = {
                "RAG_PERFORMANCE_TOKEN": "original-token",
                "RAG_PERFORMANCE_L1_BODY": original_body,
            }
            profile["workload"]["fixtureFingerprintSha256"] = (
                workflow.runtime_fixture_fingerprint(profile, environment)
            )
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            fake_jmeter = root / "jmeter.bat"
            fake_jmeter.write_text("@echo off\n", encoding="utf-8")
            stable_git = {
                "gitHead": "a" * 40,
                "gitDirty": False,
                "gitDirtyFingerprint": "b" * 64,
            }
            captured_environment: dict[str, str] = {}

            def mutate_caller_environment(*_arguments: object) -> tuple[float, float]:
                environment["RAG_PERFORMANCE_TOKEN"] = "mutated-token"
                environment["RAG_PERFORMANCE_L1_BODY"] = json.dumps(
                    {"courseId": 99, "query": "mutated", "topK": 1}
                )
                return 0.1, 0.1

            def stop_invocation(*arguments: object) -> workflow.InvocationResult:
                child_environment = arguments[7]
                assert isinstance(child_environment, dict)
                captured_environment.update(child_environment)
                return workflow.InvocationResult(
                    stop_reason={"kind": "MEMORY", "detail": "test stop"},
                    duration_seconds=1.0,
                )

            with (
                mock.patch.object(workflow, "discover_jmeter", return_value=fake_jmeter),
                mock.patch.object(workflow, "jmeter_version", return_value="5.6.3"),
                mock.patch.object(workflow, "git_snapshot", return_value=stable_git),
                mock.patch.object(
                    workflow,
                    "actuator_preflight",
                    side_effect=mutate_caller_environment,
                ),
                mock.patch.object(
                    workflow,
                    "actuator_runtime_build_fingerprint",
                    return_value="f" * 64,
                ),
                mock.patch.object(workflow, "external_call_counter", return_value=0.0),
                mock.patch.object(
                    workflow, "run_jmeter_invocation", side_effect=stop_invocation
                ),
            ):
                report_path = workflow.execute_workflow(
                    repo_root=PERFORMANCE_DIR.parents[2],
                    profile_path=profile_path,
                    jmx_path=JMX_PATH,
                    schema_path=SCHEMA_PATH,
                    output_root=root / "runs",
                    jmeter_path=None,
                    environ=environment,
                    explicit_execute=True,
                )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("STOPPED", report["runStatus"])
            self.assertEqual("original-token", captured_environment["RAG_PERFORMANCE_TOKEN"])
            self.assertEqual(original_body, captured_environment["RAG_PERFORMANCE_L1_BODY"])


class JmxContractTests(unittest.TestCase):
    def test_performance_spring_profile_exposes_only_required_local_observation_endpoints(self) -> None:
        config = (
            PERFORMANCE_DIR.parents[1]
            / "src"
            / "main"
            / "resources"
            / "application-performance.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("include: health,info,metrics", config)
        self.assertIn("runtimeBuildFingerprintSha256", config)
        self.assertIn("RAG_PERFORMANCE_RUNTIME_BUILD_FINGERPRINT_SHA256", config)

    def test_powershell_wrapper_only_dispatches_python_and_defaults_to_validation(self) -> None:
        wrapper = (PERFORMANCE_DIR / "run-performance.ps1").read_text(encoding="utf-8")
        self.assertIn("& python @arguments", wrapper)
        self.assertIn("$arguments += '--validate-only'", wrapper)
        self.assertNotIn("Start-Process", wrapper)
        self.assertNotIn("docker", wrapper.lower())

    def test_jmx_xml_and_key_samplers(self) -> None:
        summary = workflow.validate_jmx(JMX_PATH)
        self.assertEqual("5.6.3", summary["jmeterVersion"])
        self.assertEqual(
            ["L1-retrieval", "L2-ingest-poll", "L2-ingest-submit", "L3-chat"],
            summary["samplers"],
        )
        root = ET.parse(JMX_PATH).getroot()
        samplers = {node.attrib["testname"]: node for node in root.iter("HTTPSamplerProxy")}
        sampler_names = set(samplers)
        self.assertIn("L1-retrieval", sampler_names)
        self.assertIn("L2-ingest-submit", sampler_names)
        self.assertIn("L2-ingest-poll", sampler_names)
        self.assertIn("L3-chat", sampler_names)
        submit_values = {
            child.attrib.get("name"): child.text or ""
            for child in samplers["L2-ingest-submit"].findall("stringProp")
        }
        self.assertIn("/api/documents/", submit_values["HTTPSampler.path"])
        self.assertNotIn("/api/agent/documents/", submit_values["HTTPSampler.path"])
        assertions = {
            node.attrib.get("testname", ""): node for node in root.iter("ResponseAssertion")
        }
        submit_assertion_text = ET.tostring(
            assertions["L2 submit requires HTTP 202"], encoding="unicode"
        )
        self.assertIn(">202<", submit_assertion_text)

        json_assertions = {
            node.attrib.get("testname", ""): node for node in root.iter("JSONPathAssertion")
        }
        expected_json_assertions = {
            "L1 requires degraded=false": ("$.degraded", "false"),
            "L1 requires status success": ("$.status", "success"),
            "L3 requires evidence decision ANSWER": (
                "$.data.metadata.evidenceDecision.decision",
                "ANSWER",
            ),
        }
        for name, (json_path, expected_value) in expected_json_assertions.items():
            assertion = json_assertions[name]
            string_values = {
                child.attrib.get("name"): child.text or ""
                for child in assertion.findall("stringProp")
            }
            boolean_values = {
                child.attrib.get("name"): (child.text or "").lower()
                for child in assertion.findall("boolProp")
            }
            self.assertEqual(json_path, string_values["JSON_PATH"])
            self.assertEqual(expected_value, string_values["EXPECTED_VALUE"])
            self.assertEqual("true", boolean_values["JSONVALIDATION"])
            self.assertEqual("false", boolean_values["EXPECT_NULL"])
            self.assertEqual("false", boolean_values["INVERT"])
            self.assertEqual("false", boolean_values["ISREGEX"])

    def test_validate_jmx_rejects_weakened_health_or_answer_assertions(self) -> None:
        original = JMX_PATH.read_text(encoding="utf-8")
        mutations = (
            (
                '<stringProp name="EXPECTED_VALUE">false</stringProp>',
                '<stringProp name="EXPECTED_VALUE">true</stringProp>',
            ),
            (
                '<stringProp name="EXPECTED_VALUE">success</stringProp>',
                '<stringProp name="EXPECTED_VALUE">empty</stringProp>',
            ),
            (
                '<stringProp name="EXPECTED_VALUE">ANSWER</stringProp>',
                '<stringProp name="EXPECTED_VALUE">REFUSE</stringProp>',
            ),
            (
                '<boolProp name="INVERT">false</boolProp>',
                '<boolProp name="INVERT">true</boolProp>',
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            for index, (before, after) in enumerate(mutations):
                with self.subTest(mutation=after):
                    path = Path(temporary) / f"mutated-{index}.jmx"
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    with self.assertRaises(workflow.WorkflowError):
                        workflow.validate_jmx(path)

    def test_validate_jmx_rejects_duplicate_disabled_redirect_or_delayed_429_contract(self) -> None:
        original = JMX_PATH.read_text(encoding="utf-8")
        mutations = (
            original.replace(
                'testname="L3-chat" enabled="true"',
                'testname="L1-retrieval" enabled="true"',
                1,
            ),
            original.replace(
                'testname="L1-retrieval" enabled="true"',
                'testname="L1-retrieval" enabled="false"',
                1,
            ),
            original.replace(
                '<boolProp name="HTTPSampler.follow_redirects">false</boolProp>',
                '<boolProp name="HTTPSampler.follow_redirects">true</boolProp>',
                1,
            ),
            original.replace("prev.setStopTestNow(true)", "prev.setStopTestNow(false)", 1),
        )
        with tempfile.TemporaryDirectory() as temporary:
            for index, mutated in enumerate(mutations):
                with self.subTest(index=index):
                    path = Path(temporary) / f"weakened-{index}.jmx"
                    path.write_text(mutated, encoding="utf-8")
                    with self.assertRaises(workflow.WorkflowError):
                        workflow.validate_jmx(path)

    def test_validate_jmx_rejects_network_enabled_default_or_extra_sampler(self) -> None:
        original = JMX_PATH.read_text(encoding="utf-8")
        mutations = (
            original.replace(
                "props.get('PERF_VALIDATE_ONLY'\\,'true').toBoolean()",
                "props.get('PERF_VALIDATE_ONLY'\\,'false').toBoolean()",
                1,
            ),
            original.replace(
                '<HTTPSamplerProxy guiclass="HttpTestSampleGui"',
                '<HTTPSamplerProxy guiclass="HttpTestSampleGui" '
                'testname="unexpected-http" enabled="true"/><HTTPSamplerProxy '
                'guiclass="HttpTestSampleGui"',
                1,
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            for index, mutated in enumerate(mutations):
                with self.subTest(index=index):
                    path = Path(temporary) / f"guard-mutated-{index}.jmx"
                    path.write_text(mutated, encoding="utf-8")
                    with self.assertRaises(workflow.WorkflowError):
                        workflow.validate_jmx(path)

    def test_token_and_payloads_are_environment_only(self) -> None:
        text = JMX_PATH.read_text(encoding="utf-8")
        self.assertIn("System.getenv('RAG_PERFORMANCE_TOKEN')", text)
        self.assertIn("System.getenv('RAG_PERFORMANCE_L1_BODY')", text)
        self.assertIn("System.getenv('RAG_PERFORMANCE_DOCUMENT_ID_POOL')", text)
        self.assertIn("System.getenv('RAG_PERFORMANCE_L3_BODY')", text)
        self.assertNotIn("Authorization: Bearer", text)
        self.assertNotIn('"query":', text)
        self.assertNotIn('"question":', text)
        self.assertIn("$.data.reused", text)
        self.assertIn("ingest submission reused an existing job", text)

    def test_jtl_never_saves_bodies_headers_url_or_assertion_messages(self) -> None:
        root = ET.parse(JMX_PATH).getroot()
        save = root.find(".//ResultCollector/objProp/value")
        self.assertIsNotNone(save)
        for field in (
            "responseData",
            "samplerData",
            "requestHeaders",
            "responseHeaders",
            "responseDataOnError",
            "saveAssertionResultsFailureMessage",
            "url",
        ):
            self.assertEqual("false", save.find(field).text)

    def test_jmeter_command_has_no_token_payload_or_business_id(self) -> None:
        arguments = workflow.jmeter_arguments(
            JMX_PATH,
            Path("safe.jtl"),
            "http://127.0.0.1:8080",
            "L1",
            1,
            1,
            0,
            1000,
            30,
        )
        command = " ".join(arguments)
        self.assertNotIn("RAG_PERFORMANCE_TOKEN", command)
        self.assertNotIn("RAG_PERFORMANCE_L1_BODY", command)
        self.assertNotIn("RAG_PERFORMANCE_DOCUMENT_ID_POOL", command)
        self.assertNotIn("RAG_PERFORMANCE_L3_BODY", command)


if __name__ == "__main__":
    unittest.main()
