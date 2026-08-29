from __future__ import annotations

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock


ROOT = Path(__file__).resolve().parents[4]
SCRIPT = ROOT / "backend/evals/scripts/tiered_regression_gate.py"
BASELINE = ROOT / "backend/evals/ci/baselines/rag-retrieval-smoke-v1.json"
PR_POLICY = ROOT / "backend/evals/ci/pr-deterministic-policy.json"
BUSINESS_POLICY = ROOT / "backend/evals/ci/retrieval-business-policy.example.json"
CASES = ROOT / "backend/evals/datasets/rag-retrieval-smoke-v1/cases.jsonl"
WORKFLOW = ROOT / ".github/workflows/rag-tiered-regression.yml"
OWNER_DECISION_FIELDS = [
    "artifactCostBudget",
    "artifactRetentionDays",
    "branchProtectionCheckNames",
    "businessCriticalCaseIds",
    "businessMetricThresholds",
    "milestoneRef",
    "milestoneTest55Authorization",
    "milestoneTrigger",
    "scheduledCron",
]

spec = importlib.util.spec_from_file_location("tiered_regression_gate", SCRIPT)
gate = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = gate
assert spec.loader is not None
spec.loader.exec_module(gate)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any, *, allow_nan: bool = False) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=allow_nan)
        + "\n",
        encoding="utf-8",
        newline="\n",
    )


def extract_workflow_run_block(job_name: str, step_name: str) -> str:
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    job_start = lines.index(f"  {job_name}:")
    job_end = next(
        (
            index
            for index in range(job_start + 1, len(lines))
            if lines[index].startswith("  ")
            and not lines[index].startswith("    ")
            and lines[index].endswith(":")
        ),
        len(lines),
    )
    step_start = lines.index(f"      - name: {step_name}", job_start, job_end)
    run_start = lines.index("        run: |", step_start, job_end) + 1
    run_lines: list[str] = []
    for line in lines[run_start:job_end]:
        if line and not line.startswith("          "):
            break
        run_lines.append(line[10:] if line else "")
    return "\n".join(run_lines) + "\n"


def resolve_bash() -> str:
    candidates: list[Path] = []
    git = shutil.which("git")
    if git is not None:
        git_root = Path(git).resolve().parent.parent
        candidates.extend(
            [git_root / "bin/bash.exe", git_root / "usr/bin/bash.exe"]
        )
    bash = shutil.which("bash")
    if bash is not None:
        candidates.append(Path(bash))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise AssertionError("bash is required to execute workflow shell regressions")


def build_candidate() -> dict[str, Any]:
    baseline = read_json(BASELINE)
    case_inputs = {
        row["caseId"]: row
        for row in (
            json.loads(line)
            for line in CASES.read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    }
    candidate_cases = []
    for index, frozen in enumerate(baseline["cases"]):
        source = case_inputs[frozen["caseId"]]
        if frozen["answerability"] == "ANSWERABLE" and frozen["metrics"]["recallAt5"] > 0:
            returned = [
                {
                    "chunkId": source["relevantChunkIds"][0],
                    "score": 1.0,
                    "title": "synthetic relevant fixture",
                }
            ]
        elif (
            frozen["answerability"] == "UNANSWERABLE"
            and frozen["metrics"]["returnedAnyChunk"]
        ):
            returned = [
                {
                    "chunkId": 9_000_000 + index,
                    "score": 0.0,
                    "title": "synthetic non-answer fixture",
                }
            ]
        else:
            returned = []
        candidate_cases.append(
            {
                "caseId": frozen["caseId"],
                "query": source["query"],
                "answerability": frozen["answerability"],
                "tags": source["tags"],
                "relevantChunkIds": source["relevantChunkIds"],
                "returned": returned,
                "metrics": copy.deepcopy(frozen["metrics"]),
                "latencyMs": 0,
            }
        )
    return {
        "evaluationType": baseline["evaluationType"],
        "evidenceBoundary": "Synthetic offline smoke used only for deterministic engineering regression plumbing.",
        "datasetVersion": baseline["datasetVersion"],
        "executedAt": "2026-08-25T00:00:00Z",
        "codeRevision": "test-revision",
        "worktreeFingerprint": "test-worktree-fingerprint",
        "configuration": {
            "embedding": "MockEmbeddingClient/hash",
            "dimension": 64,
            "vectorStore": "InMemoryConsistentVectorStore",
            "reranker": "LocalLexicalKnowledgeReranker(0.7,0.3)",
            "candidateK": 10,
            "topK": 5,
            "activeVersionIds": [101],
        },
        "overall": copy.deepcopy(baseline["overall"]),
        "cases": candidate_cases,
    }


V2_METRICS = (
    "recallAt10",
    "sourceCoverageAt10",
    "evidenceGroupCoverageAt10",
)


def encoded_json(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False)
        + "\n"
    ).encode("utf-8")


def build_v2_bundle(*, has_critical: bool = True) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    cases = [
        {
            "caseId": "C1",
            "query": "critical synthetic query",
            "answerability": "ANSWERABLE",
            "severity": "CRITICAL" if has_critical else "STANDARD",
            "tags": ["business"],
            "relevantEvidence": [
                {"chunkId": 101, "sourceId": "SRC_A", "evidenceGroupId": "GROUP_1"},
                {"chunkId": 102, "sourceId": "SRC_B", "evidenceGroupId": "GROUP_2"},
            ],
            "returned": [
                {
                    "chunkId": 101,
                    "score": 1.0,
                    "title": "synthetic result A",
                    "sourceId": "SRC_A",
                    "evidenceGroupId": "GROUP_1",
                },
                {
                    "chunkId": 102,
                    "score": 0.9,
                    "title": "synthetic result B",
                    "sourceId": "SRC_B",
                    "evidenceGroupId": "GROUP_2",
                },
            ],
            "metrics": {metric: 1.0 for metric in V2_METRICS},
            "latencyMs": 0,
        },
        {
            "caseId": "S1",
            "query": "standard synthetic query",
            "answerability": "ANSWERABLE",
            "severity": "STANDARD",
            "tags": ["CRITICAL"],
            "relevantEvidence": [
                {"chunkId": 201, "sourceId": "SRC_C", "evidenceGroupId": "GROUP_3"}
            ],
            "returned": [
                {
                    "chunkId": 201,
                    "score": 1.0,
                    "title": "synthetic result C",
                    "sourceId": "SRC_C",
                    "evidenceGroupId": "GROUP_3",
                }
            ],
            "metrics": {metric: 1.0 for metric in V2_METRICS},
            "latencyMs": 0,
        },
    ]
    candidate = {
        "schema": "rag-tiered-regression-candidate/v2",
        "evaluationType": "OFFLINE_BUSINESS_RETRIEVAL",
        "evidenceBoundary": "Synthetic v2 contract evidence only.",
        "datasetVersion": "rag-business-contract-v2",
        "executedAt": "2026-08-25T00:00:00Z",
        "codeRevision": "test-v2-revision",
        "worktreeFingerprint": "test-v2-worktree",
        "configuration": {
            "embedding": "MockEmbeddingClient/hash",
            "dimension": 64,
            "vectorStore": "InMemoryConsistentVectorStore",
            "reranker": "LocalLexicalKnowledgeReranker(0.7,0.3)",
            "candidateK": 10,
            "topK": 10,
            "activeVersionIds": [101],
        },
        "overall": {
            "caseCount": 2,
            "answerableCaseCount": 2,
            "unanswerableCaseCount": 0,
            **{metric: 1.0 for metric in V2_METRICS},
        },
        "cases": cases,
    }
    identity = gate.inspect_candidate_identity_v2(candidate)
    baseline = {
        "schema": "rag-tiered-regression-baseline/v2",
        "aggregateOnly": True,
        "rawSamplesIncluded": False,
        "baselineId": "rag-business-contract-v2",
        "sourceRevision": "test-v2-source",
        "evaluationType": candidate["evaluationType"],
        "datasetVersion": candidate["datasetVersion"],
        "configurationFingerprint": identity.configuration_fingerprint,
        "caseSetFingerprint": identity.case_set_fingerprint,
        "datasetInputFingerprint": identity.dataset_input_fingerprint,
        "overall": copy.deepcopy(candidate["overall"]),
        "cases": [
            {
                "caseId": case["caseId"],
                "answerability": case["answerability"],
                "severity": case["severity"],
                "inputFingerprint": identity.case_input_fingerprints[case["caseId"]],
                "metrics": copy.deepcopy(case["metrics"]),
            }
            for case in cases
        ],
    }
    policy = {
        "schema": "rag-tiered-regression-policy/v2",
        "configured": True,
        "deferredOwnerFields": [],
        "ownerDecisionRequired": False,
        "policyId": "business-no-regression-v2",
        "policyScope": "BUSINESS_RELEASE_QUALITY",
        "tier": "MILESTONE",
        "baselineId": baseline["baselineId"],
        "baselineSha256": hashlib.sha256(encoded_json(baseline)).hexdigest(),
        "evaluationType": baseline["evaluationType"],
        "datasetVersion": baseline["datasetVersion"],
        "configurationFingerprint": baseline["configurationFingerprint"],
        "caseSetFingerprint": baseline["caseSetFingerprint"],
        "datasetInputFingerprint": baseline["datasetInputFingerprint"],
        "frozenCaseIds": ["C1", "S1"],
        "comparisonRules": {
            "businessOverall": {metric: "NOT_LOWER" for metric in V2_METRICS},
            "criticalCases": {metric: "NOT_LOWER" for metric in V2_METRICS},
        },
    }
    return candidate, baseline, policy


def set_v2_case_miss(candidate: dict[str, Any], case_id: str) -> None:
    target = next(case for case in candidate["cases"] if case["caseId"] == case_id)
    target["returned"] = []
    target["metrics"] = {metric: 0.0 for metric in V2_METRICS}
    for metric in V2_METRICS:
        candidate["overall"][metric] = sum(
            float(case["metrics"][metric]) for case in candidate["cases"]
        ) / len(candidate["cases"])


class TieredRegressionGateTest(unittest.TestCase):
    maxDiff = None

    def run_gate(
        self,
        *,
        candidate: dict[str, Any] | None = None,
        candidate_raw: str | None = None,
        candidate_missing: bool = False,
        policy: dict[str, Any] | None = None,
        policy_path: Path | None = None,
        baseline: dict[str, Any] | None = None,
        existing_output: bytes | None = None,
        cleanup_error: OSError | None = None,
    ) -> tuple[int, dict[str, Any] | None, bytes | None, list[str]]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline_path = root / "baseline.json"
            if baseline is None:
                shutil.copyfile(BASELINE, baseline_path)
            else:
                write_json(baseline_path, baseline)
            if policy_path is not None:
                selected_policy = policy_path
            else:
                selected_policy = root / "policy.json"
                write_json(selected_policy, policy if policy is not None else read_json(PR_POLICY))
            candidate_path = root / "candidate.json"
            if not candidate_missing:
                if candidate_raw is not None:
                    candidate_path.write_text(candidate_raw, encoding="utf-8", newline="\n")
                else:
                    write_json(candidate_path, candidate if candidate is not None else build_candidate())
            output = root / "result.json"
            if existing_output is not None:
                output.write_bytes(existing_output)
            cleanup_context = (
                mock.patch.object(gate.os, "unlink", side_effect=cleanup_error)
                if cleanup_error is not None
                else contextlib.nullcontext()
            )
            with cleanup_context, contextlib.redirect_stdout(
                io.StringIO()
            ), contextlib.redirect_stderr(io.StringIO()):
                exit_code = gate.main(
                    [
                        "--baseline",
                        str(baseline_path),
                        "--candidate",
                        str(candidate_path),
                        "--policy",
                        str(selected_policy),
                        "--output",
                        str(output),
                    ]
                )
            output_bytes = output.read_bytes() if output.exists() else None
            report = None
            if output_bytes is not None and existing_output is None:
                report = json.loads(output_bytes.decode("utf-8"))
            names = sorted(path.name for path in root.iterdir())
            return exit_code, report, output_bytes, names

    def run_workflow_gate_shell(
        self,
        *,
        job_name: str,
        step_name: str,
        gate_code: int,
        result_json: str | None,
    ) -> subprocess.CompletedProcess[str]:
        shell_block = extract_workflow_run_block(job_name, step_name)
        python_stub = r'''python() {
  local is_gate=0
  local expect_output=0
  local output=""
  local argument
  for argument in "$@"; do
    if [ "$expect_output" -eq 1 ]; then
      output="$argument"
      expect_output=0
    elif [ "$argument" = "--output" ]; then
      expect_output=1
    fi
    if [[ "$argument" == *"tiered_regression_gate.py"* ]]; then
      is_gate=1
    fi
  done
  if [ "$is_gate" -eq 1 ]; then
    if [ "$STUB_WRITE_RESULT" = "1" ]; then
      printf '%s\n' "$STUB_RESULT_JSON" > "$output"
    fi
    return "$STUB_GATE_CODE"
  fi
  "$REAL_PYTHON" "$@"
}
'''
        with tempfile.TemporaryDirectory() as temporary:
            environment = os.environ.copy()
            environment.update(
                {
                    "REAL_PYTHON": Path(sys.executable).resolve().as_posix(),
                    "RUNNER_TEMP": Path(temporary).as_posix(),
                    "STUB_GATE_CODE": str(gate_code),
                    "STUB_RESULT_JSON": result_json or "",
                    "STUB_WRITE_RESULT": "0" if result_json is None else "1",
                }
            )
            return subprocess.run(
                [resolve_bash(), "-c", python_stub + shell_block],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

    def assert_workflow_gate_shell_contract(
        self, *, job_name: str, step_name: str
    ) -> None:
        valid_deferred = json.dumps(
            {
                "schema": "rag-tiered-regression-result/v1",
                "verdict": "OWNER_DECISION_DEFERRED",
                "exitCode": gate.OWNER_DECISION_DEFERRED,
                "policy": {"configured": False},
                "ownerDecisionDeferred": OWNER_DECISION_FIELDS,
            },
            separators=(",", ":"),
        )
        for gate_code in range(gate.OWNER_DECISION_DEFERRED + 1):
            with self.subTest(job=job_name, gate_code=gate_code):
                completed = self.run_workflow_gate_shell(
                    job_name=job_name,
                    step_name=step_name,
                    gate_code=gate_code,
                    result_json=valid_deferred,
                )
                self.assertEqual(
                    gate_code,
                    completed.returncode,
                    msg=f"stdout={completed.stdout!r} stderr={completed.stderr!r}",
                )

        invalid_results = {
            "missing": None,
            "invalid-json": "{",
            "invalid-structure": json.dumps(
                {"verdict": "PASS", "exitCode": gate.PASS}, separators=(",", ":")
            ),
        }
        for name, result_json in invalid_results.items():
            with self.subTest(job=job_name, invalid_result=name):
                completed = self.run_workflow_gate_shell(
                    job_name=job_name,
                    step_name=step_name,
                    gate_code=gate.OWNER_DECISION_DEFERRED,
                    result_json=result_json,
                )
                self.assertEqual(
                    gate.OPERATION_ERROR,
                    completed.returncode,
                    msg=f"stdout={completed.stdout!r} stderr={completed.stderr!r}",
                )

    def test_v2_recomputes_at10_metrics_and_separates_overall_from_critical_gates(self) -> None:
        candidate, baseline, policy = build_v2_bundle()

        exit_code, report, _, _ = self.run_gate(
            candidate=candidate, baseline=baseline, policy=policy
        )

        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual("rag-tiered-regression-result/v2", report["schema"])
        self.assertEqual(["C1"], report["identity"]["criticalCaseIds"])
        self.assertEqual(6, report["comparison"]["checkedMetricCount"])

        misreported = copy.deepcopy(candidate)
        misreported["cases"][0]["metrics"]["sourceCoverageAt10"] = 0.5
        exit_code, report, _, _ = self.run_gate(
            candidate=misreported, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertIn(
            {
                "code": "CANDIDATE_EVIDENCE_INVALID",
                "reason": "V2_CASE_METRIC_INCONSISTENT",
            },
            report["findings"],
        )

        standard_regression = copy.deepcopy(candidate)
        set_v2_case_miss(standard_regression, "S1")
        exit_code, report, _, _ = self.run_gate(
            candidate=standard_regression, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.CODE_REGRESSION, exit_code)
        regression_scopes = {
            item["scope"]
            for item in report["comparison"]["differences"]
            if item["classification"] == "REGRESSION"
        }
        self.assertEqual({"businessOverall"}, regression_scopes)

        critical_regression = copy.deepcopy(candidate)
        set_v2_case_miss(critical_regression, "C1")
        exit_code, report, _, _ = self.run_gate(
            candidate=critical_regression, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.CODE_REGRESSION, exit_code)
        regression_scopes = {
            item["scope"]
            for item in report["comparison"]["differences"]
            if item["classification"] == "REGRESSION"
        }
        self.assertEqual({"businessOverall", "criticalCase"}, regression_scopes)

    def test_v2_critical_selector_uses_only_dataset_severity_and_allows_empty_set(self) -> None:
        candidate, baseline, policy = build_v2_bundle()
        exit_code, report, _, _ = self.run_gate(
            candidate=candidate, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual(["C1"], report["identity"]["criticalCaseIds"])
        self.assertNotIn("S1", report["identity"]["criticalCaseIds"])

        candidate, baseline, policy = build_v2_bundle(has_critical=False)
        exit_code, report, _, _ = self.run_gate(
            candidate=candidate, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual([], report["identity"]["criticalCaseIds"])
        self.assertEqual(3, report["comparison"]["checkedMetricCount"])

        policy["criticalCaseIds"] = ["S1"]
        exit_code, report, _, _ = self.run_gate(
            candidate=candidate, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.OPERATION_ERROR, exit_code)
        self.assertIsNone(report)

    def test_v2_requires_top_k_ten_and_complete_source_group_evidence(self) -> None:
        candidate, baseline, policy = build_v2_bundle()
        too_shallow = copy.deepcopy(candidate)
        too_shallow["configuration"]["topK"] = 5
        exit_code, report, _, _ = self.run_gate(
            candidate=too_shallow, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertIn(
            {"code": "CANDIDATE_EVIDENCE_INVALID", "reason": "V2_TOP_K_TOO_SMALL"},
            report["findings"],
        )

        missing_returned_source = copy.deepcopy(candidate)
        del missing_returned_source["cases"][0]["returned"][0]["sourceId"]
        exit_code, report, _, _ = self.run_gate(
            candidate=missing_returned_source, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertIn(
            {
                "code": "CANDIDATE_EVIDENCE_INVALID",
                "reason": "V2_RETURNED_EVIDENCE_INVALID",
            },
            report["findings"],
        )

        missing_group_truth = copy.deepcopy(candidate)
        del missing_group_truth["cases"][0]["relevantEvidence"][0]["evidenceGroupId"]
        exit_code, report, _, _ = self.run_gate(
            candidate=missing_group_truth, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertIn(
            {"code": "CANDIDATE_EVIDENCE_INVALID", "reason": "V2_CASE_INPUT_INCOMPLETE"},
            report["findings"],
        )

    def test_v1_v2_schema_mixing_fails_closed(self) -> None:
        v2_candidate, v2_baseline, v2_policy = build_v2_bundle()
        scenarios = [
            (
                "v2-policy-v1-baseline",
                build_candidate(),
                read_json(BASELINE),
                v2_policy,
                "rag-tiered-regression-result/v2",
                "BASELINE_SCHEMA_VERSION_MISMATCH",
            ),
            (
                "v2-policy-v1-candidate",
                build_candidate(),
                v2_baseline,
                v2_policy,
                "rag-tiered-regression-result/v2",
                "CANDIDATE_SCHEMA_VERSION_MISMATCH",
            ),
            (
                "v1-policy-v2-candidate",
                v2_candidate,
                read_json(BASELINE),
                read_json(PR_POLICY),
                "rag-tiered-regression-result/v1",
                "CANDIDATE_SCHEMA_VERSION_MISMATCH",
            ),
        ]
        for name, candidate, baseline, policy, result_schema, reason in scenarios:
            with self.subTest(name=name):
                exit_code, report, _, _ = self.run_gate(
                    candidate=candidate, baseline=baseline, policy=policy
                )
                self.assertEqual(gate.INFRA_FAILURE, exit_code)
                self.assertEqual(result_schema, report["schema"])
                self.assertTrue(
                    any(item.get("reason") == reason for item in report["findings"]),
                    report["findings"],
                )

    def test_v2_severity_changes_case_input_fingerprint(self) -> None:
        candidate, baseline, policy = build_v2_bundle()
        changed = copy.deepcopy(candidate)
        changed["cases"][0]["severity"] = "STANDARD"

        original_identity = gate.inspect_candidate_identity_v2(candidate)
        changed_identity = gate.inspect_candidate_identity_v2(changed)
        self.assertNotEqual(
            original_identity.case_input_fingerprints["C1"],
            changed_identity.case_input_fingerprints["C1"],
        )

        exit_code, report, _, _ = self.run_gate(
            candidate=changed, baseline=baseline, policy=policy
        )
        self.assertEqual(gate.DATA_MODEL_DRIFT, exit_code)
        self.assertIn(
            {"code": "CASE_INPUT_FINGERPRINT_MISMATCH", "caseId": "C1"},
            report["findings"],
        )

    def test_current_fixture_candidate_passes(self) -> None:
        candidate = build_candidate()
        identity = gate.inspect_candidate_identity(candidate)
        baseline = read_json(BASELINE)
        self.assertEqual(baseline["configurationFingerprint"], identity.configuration_fingerprint)
        self.assertEqual(baseline["caseSetFingerprint"], identity.case_set_fingerprint)
        self.assertEqual(baseline["datasetInputFingerprint"], identity.dataset_input_fingerprint)

        exit_code, report, _, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual("PASS", report["verdict"])
        self.assertEqual(71, report["comparison"]["checkedMetricCount"])
        self.assertEqual([], report["comparison"]["differences"])

    def test_lowered_case_metric_is_code_regression_with_exact_diff(self) -> None:
        candidate = build_candidate()
        target = next(case for case in candidate["cases"] if case["caseId"] == "S04")
        target["returned"] = []
        for metric in ("recallAt1", "recallAt3", "recallAt5", "reciprocalRank", "ndcgAt5"):
            target["metrics"][metric] = 0.0
        for metric in ("recallAt1", "recallAt3", "recallAt5", "mrr", "ndcgAt5"):
            candidate["overall"][metric] = 0.1

        exit_code, report, _, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.CODE_REGRESSION, exit_code)
        self.assertEqual("CODE_REGRESSION", report["verdict"])
        regressions = [
            item
            for item in report["comparison"]["differences"]
            if item["classification"] == "REGRESSION"
            and item.get("caseId") == "S04"
            and item["metric"] == "recallAt5"
        ]
        self.assertEqual(
            [
                {
                    "scope": "case",
                    "caseId": "S04",
                    "metric": "recallAt5",
                    "rule": "NOT_LOWER",
                    "baseline": 1.0,
                    "candidate": 0.0,
                    "delta": -1.0,
                    "classification": "REGRESSION",
                }
            ],
            regressions,
        )
        self.assertEqual(10, report["comparison"]["regressionCount"])

    def test_metadata_configuration_and_case_set_mismatch_are_data_model_drift(self) -> None:
        mutations = {}
        candidate = build_candidate()
        candidate["evaluationType"] = "OTHER_EVALUATION"
        mutations["evaluation"] = candidate
        candidate = build_candidate()
        candidate["datasetVersion"] = "other-dataset-v1"
        mutations["dataset"] = candidate
        candidate = build_candidate()
        candidate["configuration"]["dimension"] = 32
        mutations["configuration"] = candidate
        candidate = build_candidate()
        candidate["cases"].pop()
        candidate["overall"]["caseCount"] = 11
        candidate["overall"]["unanswerableCaseCount"] = 1
        mutations["case-set"] = candidate
        candidate = build_candidate()
        candidate["cases"][0]["query"] = "changed synthetic query"
        mutations["case-input"] = candidate

        for name, changed in mutations.items():
            with self.subTest(name=name):
                exit_code, report, _, _ = self.run_gate(candidate=changed)
                self.assertEqual(gate.DATA_MODEL_DRIFT, exit_code)
                self.assertEqual("DATA_MODEL_DRIFT", report["verdict"])
                self.assertEqual(0, report["comparison"]["checkedMetricCount"])

    def test_unknown_frozen_fixture_case_is_data_model_drift(self) -> None:
        policy = read_json(PR_POLICY)
        policy["frozenFixtureCaseIds"].append("S99")

        exit_code, report, _, _ = self.run_gate(policy=policy)

        self.assertEqual(gate.DATA_MODEL_DRIFT, exit_code)
        self.assertIn(
            {"code": "POLICY_FIXTURE_CASE_UNKNOWN", "caseId": "S99"},
            report["findings"],
        )

    def test_missing_malformed_incomplete_and_nonfinite_evidence_are_infra_failure(self) -> None:
        incomplete = build_candidate()
        del incomplete["cases"][0]["metrics"]["recallAt5"]
        bool_as_number = build_candidate()
        bool_as_number["overall"]["recallAt1"] = True
        nonfinite = build_candidate()
        nonfinite["cases"][3]["metrics"]["recallAt5"] = float("nan")
        scenarios = [
            {"candidate_missing": True},
            {"candidate_raw": "{"},
            {"candidate": incomplete},
            {"candidate": bool_as_number},
            {
                "candidate_raw": json.dumps(
                    nonfinite, ensure_ascii=False, allow_nan=True, sort_keys=True
                )
            },
        ]

        for index, arguments in enumerate(scenarios):
            with self.subTest(index=index):
                exit_code, report, _, _ = self.run_gate(**arguments)
                self.assertEqual(gate.INFRA_FAILURE, exit_code)
                self.assertEqual("INFRA_FAILURE", report["verdict"])
                self.assertEqual(0, report["comparison"]["checkedMetricCount"])

    def test_reported_metrics_must_match_returned_evidence(self) -> None:
        candidate = build_candidate()
        target = next(case for case in candidate["cases"] if case["caseId"] == "S04")
        target["returned"] = []

        exit_code, report, _, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertIn(
            {
                "code": "CANDIDATE_EVIDENCE_INVALID",
                "reason": "CASE_METRIC_INCONSISTENT",
            },
            report["findings"],
        )

    def test_invalid_execution_evidence_precedes_identity_drift(self) -> None:
        candidate = build_candidate()
        candidate["configuration"]["dimension"] = 32
        del candidate["cases"][0]["metrics"]["recallAt5"]

        exit_code, report, _, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.INFRA_FAILURE, exit_code)
        self.assertEqual("INFRA_FAILURE", report["verdict"])
        self.assertEqual(0, report["comparison"]["checkedMetricCount"])

    def test_candidate_only_case_id_is_not_copied_to_drift_result(self) -> None:
        candidate = build_candidate()
        sentinel = "SENSITIVE_CASE_SENTINEL"
        candidate["cases"].append(
            {
                "caseId": sentinel,
                "query": "synthetic extra query",
                "answerability": "UNANSWERABLE",
                "tags": ["synthetic-extra"],
                "relevantChunkIds": [],
                "returned": [],
                "metrics": {"returnedAnyChunk": False, "staleVersionLeakCount": 0},
                "latencyMs": 0,
            }
        )
        candidate["overall"]["caseCount"] = 13
        candidate["overall"]["unanswerableCaseCount"] = 3
        candidate["overall"]["noAnswerFalsePositiveRate"] = 2.0 / 3.0

        exit_code, report, output_bytes, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.DATA_MODEL_DRIFT, exit_code)
        self.assertNotIn(sentinel, output_bytes.decode("utf-8"))
        self.assertIn(
            {
                "code": "CANDIDATE_CASE_SET_MISMATCH",
                "missingCaseIds": [],
                "extraCaseCount": 1,
            },
            report["findings"],
        )

    def test_unconfigured_business_policy_is_owner_decision_deferred(self) -> None:
        exit_code, report, _, _ = self.run_gate(policy_path=BUSINESS_POLICY)

        self.assertEqual(gate.OWNER_DECISION_DEFERRED, exit_code)
        self.assertEqual("OWNER_DECISION_DEFERRED", report["verdict"])
        self.assertFalse(report["policy"]["configured"])
        self.assertNotEqual("PASS", report["verdict"])
        self.assertIn("businessMetricThresholds", report["ownerDecisionDeferred"])
        self.assertIn("businessCriticalCaseIds", report["ownerDecisionDeferred"])

    def test_existing_output_is_not_overwritten(self) -> None:
        original = b"do-not-overwrite\n"

        exit_code, report, output_bytes, _ = self.run_gate(existing_output=original)

        self.assertEqual(gate.OPERATION_ERROR, exit_code)
        self.assertIsNone(report)
        self.assertEqual(original, output_bytes)

    def test_invalid_policy_is_operation_error(self) -> None:
        policy = read_json(PR_POLICY)
        policy["schema"] = "wrong-policy/v1"

        exit_code, report, output_bytes, _ = self.run_gate(policy=policy)

        self.assertEqual(gate.OPERATION_ERROR, exit_code)
        self.assertIsNone(report)
        self.assertIsNone(output_bytes)

    def test_result_is_aggregate_only_and_does_not_copy_raw_candidate_values(self) -> None:
        candidate = build_candidate()
        sentinel = "SYNTHETIC_SECRET_SENTINEL_DO_NOT_COPY"
        candidate["cases"][0]["returned"] = [
            {"chunkId": 1, "score": 0.0, "title": sentinel}
        ]

        exit_code, report, output_bytes, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.PASS, exit_code)
        serialized = output_bytes.decode("utf-8")
        self.assertTrue(report["aggregateOnly"])
        self.assertFalse(report["rawSamplesIncluded"])
        self.assertNotIn(sentinel, serialized)
        self.assertNotIn(str(CASES), serialized)
        for case in candidate["cases"]:
            self.assertNotIn(case["query"], serialized)

    def test_improvement_passes_and_is_reported(self) -> None:
        candidate = build_candidate()
        target = next(case for case in candidate["cases"] if case["caseId"] == "S11")
        target["returned"] = []
        target["metrics"]["returnedAnyChunk"] = False
        candidate["overall"]["noAnswerFalsePositiveRate"] = 0.5

        exit_code, report, _, _ = self.run_gate(candidate=candidate)

        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual(2, report["comparison"]["improvementCount"])
        self.assertEqual(0, report["comparison"]["regressionCount"])

    def test_baseline_hash_tamper_is_data_model_drift(self) -> None:
        baseline = read_json(BASELINE)
        baseline["sourceRevision"] = "tampered-but-structurally-valid"

        exit_code, report, _, _ = self.run_gate(baseline=baseline)

        self.assertEqual(gate.DATA_MODEL_DRIFT, exit_code)
        self.assertIn({"code": "BASELINE_HASH_MISMATCH"}, report["findings"])

    def test_atomic_publish_leaves_no_temporary_file(self) -> None:
        exit_code, _, _, names = self.run_gate()

        self.assertEqual(gate.PASS, exit_code)
        self.assertEqual(
            ["baseline.json", "candidate.json", "policy.json", "result.json"], names
        )

    def test_publish_race_preserves_winner_and_cleans_temporary_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "result.json"
            winner = b"concurrent-winner\n"

            def publish_competitor(_: str, __: Path) -> None:
                output.write_bytes(winner)
                raise FileExistsError

            with mock.patch.object(gate.os, "link", side_effect=publish_competitor):
                with self.assertRaises(gate.OperationError):
                    gate.write_result_exclusive(output, {"verdict": "PASS"})

            self.assertEqual(winner, output.read_bytes())
            self.assertEqual(["result.json"], sorted(path.name for path in root.iterdir()))

    def test_main_reports_operation_error_when_cleanup_fails_after_publish(self) -> None:
        exit_code, report, output_bytes, names = self.run_gate(
            cleanup_error=OSError("injected cleanup failure")
        )

        self.assertEqual(gate.OPERATION_ERROR, exit_code)
        self.assertNotEqual(gate.PASS, exit_code)
        self.assertEqual("PASS", report["verdict"])
        self.assertIsNotNone(output_bytes)
        self.assertIn("result.json", names)
        temporary_names = [
            name
            for name in names
            if name.startswith(".result.json.") and name.endswith(".tmp")
        ]
        self.assertEqual(1, len(temporary_names))

    def test_cleanup_failure_keeps_operation_error_cause_after_publish(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "result.json"
            cleanup_error = OSError("injected cleanup failure")

            with mock.patch.object(gate.os, "unlink", side_effect=cleanup_error):
                with self.assertRaisesRegex(
                    gate.OperationError, "^temporary result cleanup failed$"
                ) as raised:
                    gate.write_result_exclusive(output, {"verdict": "PASS"})

            self.assertIs(cleanup_error, raised.exception.__cause__)
            self.assertTrue(output.exists())
            temporary_names = [
                path.name
                for path in root.iterdir()
                if path.name.startswith(".result.json.") and path.name.endswith(".tmp")
            ]
            self.assertEqual(1, len(temporary_names))

    def test_scheduled_job_shell_preserves_gate_exit_contract(self) -> None:
        self.assert_workflow_gate_shell_contract(
            job_name="scheduled-preparation",
            step_name="Stop at the unconfigured business-policy boundary",
        )

    def test_milestone_job_shell_preserves_gate_exit_contract(self) -> None:
        self.assert_workflow_gate_shell_contract(
            job_name="milestone-boundary",
            step_name="Stop before any frozen release-suite execution",
        )

    def test_workflow_has_safe_pr_scheduled_and_milestone_boundaries(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("pull_request:", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("schedule:", workflow)
        self.assertNotIn("retention-days", workflow)
        self.assertNotIn("upload-artifact", workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertIn("permissions:\n  contents: read", workflow)
        self.assertIn('PYTHONDONTWRITEBYTECODE: "1"', workflow)
        self.assertNotIn("run: python -X utf8", workflow)
        self.assertNotIn("python -m json.tool", workflow)
        self.assertIn("python -B -X utf8", workflow)

        pr = workflow.split("  pr-deterministic:", 1)[1].split(
            "  scheduled-preparation:", 1
        )[0]
        self.assertIn("RetrievalMetricsCalculatorTest", pr)
        self.assertIn("RetrievalMetricsCoreArchitectureTest", pr)
        self.assertIn("RagRetrievalSmokeEvalTest", pr)
        self.assertIn("tiered_regression_gate.py", pr)
        for forbidden in (
            "docker compose",
            "run_retrieval_fixture_smoke.ps1",
            "ReviewedDev45",
            "performance",
        ):
            self.assertNotIn(forbidden, pr)

        scheduled = workflow.split("  scheduled-preparation:", 1)[1].split(
            "  milestone-boundary:", 1
        )[0]
        self.assertIn("run_retrieval_fixture_smoke.ps1", scheduled)
        self.assertIn("retrieval-business-policy.example.json", scheduled)
        self.assertIn("exit 5", scheduled)
        self.assertNotIn("docker compose", scheduled)

        milestone = workflow.split("  milestone-boundary:", 1)[1]
        self.assertIn("retrieval-business-policy.example.json", milestone)
        self.assertIn("exit 5", milestone)
        self.assertNotIn("mvn ", milestone)
        self.assertNotIn("-Dtest=", milestone)


if __name__ == "__main__":
    unittest.main()
