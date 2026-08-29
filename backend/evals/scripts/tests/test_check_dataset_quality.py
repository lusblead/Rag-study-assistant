from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_dataset_quality.py"
SPEC = importlib.util.spec_from_file_location("check_dataset_quality", SCRIPT)
assert SPEC and SPEC.loader
quality = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = quality
SPEC.loader.exec_module(quality)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
        newline="\n",
    )


def refresh_public(root: Path) -> None:
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for key in ("corpus", "cases", "embeddings"):
        entry = manifest["files"][key]
        path = root / entry["file"]
        entry["sha256"] = sha256(path)
        entry["records"] = sum(bool(line.strip()) for line in path.read_text(encoding="utf-8").splitlines())
    manifest["expectedChunkCount"] = manifest["files"]["corpus"]["records"]
    manifest["expectedCaseCount"] = manifest["files"]["cases"]["records"]
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    names = ["cases.jsonl", "corpus.jsonl", "embeddings.jsonl", "manifest.json"]
    (root / "checksums.sha256").write_text(
        "".join(f"{sha256(root / name)}  {name}\n" for name in names),
        encoding="utf-8",
        newline="\n",
    )


def make_public(root: Path) -> None:
    corpus = [
        {"chunkId": 1, "content": "Atlas cache entries expire after ten minutes.", "sourceId": "source-atlas", "license": "CC0-1.0"},
        {"chunkId": 2, "content": "Nimbus queues retry a job once.", "sourceId": "source-nimbus", "license": "CC0-1.0"},
    ]
    cases = [
        {"caseId": "q1", "query": "When do Atlas entries expire?", "answerable": True, "relevantChunkIds": [1], "acceptableChunkIds": [1], "requiredEvidenceGroups": [{"groupId": "g1", "chunkIds": [1]}], "requiredSourceIds": ["source-atlas"], "confusingChunkIds": [2]},
        {"caseId": "q2", "query": "What color is the Nimbus queue?", "answerable": False, "relevantChunkIds": [], "acceptableChunkIds": [], "requiredEvidenceGroups": [], "requiredSourceIds": [], "confusingChunkIds": [2]},
    ]
    embeddings = [
        {"embeddingId": "c1", "itemType": "chunk", "chunkId": 1, "vector": [1.0, 0.0]},
        {"embeddingId": "c2", "itemType": "chunk", "chunkId": 2, "vector": [0.0, 1.0]},
        {"embeddingId": "q1", "itemType": "query", "caseId": "q1", "vector": [1.0, 0.0]},
        {"embeddingId": "q2", "itemType": "query", "caseId": "q2", "vector": [0.0, 1.0]},
    ]
    write_jsonl(root / "corpus.jsonl", corpus)
    write_jsonl(root / "cases.jsonl", cases)
    write_jsonl(root / "embeddings.jsonl", embeddings)
    (root / "SOURCES_AND_LICENSE.md").write_text("source-atlas\nsource-nimbus\nCC0-1.0\n", encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "fixture",
        "sourceType": "synthetic-original",
        "license": "CC0-1.0",
        "expectedChunkCount": 2,
        "expectedCaseCount": 2,
        "embeddingDimension": 2,
        "files": {
            "corpus": {"file": "corpus.jsonl", "records": 0, "sha256": "0" * 64},
            "cases": {"file": "cases.jsonl", "records": 0, "sha256": "0" * 64},
            "embeddings": {"file": "embeddings.jsonl", "records": 0, "sha256": "0" * 64},
        },
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    refresh_public(root)


def make_private(root: Path) -> None:
    corpus = [
        {"chunk_id": "c1", "chunk_business_key": "b1", "content": "Private fixture alpha.", "source_document": "doc-alpha", "source_path": "obsidian:fixture/alpha.md", "source_kind": "markdown"},
        {"chunk_id": "c2", "chunk_business_key": "b2", "content": "Private fixture beta.", "source_document": "doc-beta", "source_path": "obsidian:fixture/beta.md", "source_kind": "markdown"},
    ]
    cases = [
        {"id": "q1", "case_key": "k1", "question": "What is alpha?", "answerable": True, "relevant_chunk_ids": ["c1"], "acceptable_chunk_ids": ["c1"], "required_evidence_groups": [{"fact": "alpha", "chunk_ids": ["c1"]}], "evidence": [{"chunk_id": "c1", "fact": "alpha", "quote": "alpha"}], "confusing_chunks": [], "evidence_signature": "sig1", "split": "dev", "review_status": "reviewed", "reviewer": "ChatGPT", "human_verdict": "accepted", "source_document": "doc-alpha"},
        {"id": "q2", "case_key": "k2", "question": "What is beta?", "answerable": True, "relevant_chunk_ids": ["c2"], "acceptable_chunk_ids": ["c2"], "required_evidence_groups": [{"fact": "beta", "chunk_ids": ["c2"]}], "evidence": [{"chunk_id": "c2", "fact": "beta", "quote": "beta"}], "confusing_chunks": [], "evidence_signature": "sig2", "split": "test", "review_status": "reviewed", "reviewer": "ChatGPT", "human_verdict": "accepted", "source_document": "doc-beta"},
    ]
    write_jsonl(root / "corpus.jsonl", corpus)
    write_jsonl(root / "standard_reviewed_100_retrieval.jsonl", cases)
    write_jsonl(root / "standard_reviewed_100_retrieval_dev.jsonl", [cases[0]])
    write_jsonl(root / "standard_reviewed_100_retrieval_test.jsonl", [cases[1]])
    manifest = {
        "schemaVersion": 1,
        "datasetId": "private-fixture",
        "corpusSha256": sha256(root / "corpus.jsonl"),
        "retrievalCaseFile": "standard_reviewed_100_retrieval.jsonl",
        "retrievalCaseSha256": sha256(root / "standard_reviewed_100_retrieval.jsonl"),
        "expectedChunkCount": 2,
        "expectedCaseCount": 2,
        "splitCounts": {"dev": 1, "test": 1},
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


class DatasetQualityTest(unittest.TestCase):
    def run_public(self, root: Path, output: Path) -> int:
        return quality.main(["--profile", "public-small-v1", "--dataset-dir", str(root), "--output", str(output)])

    def test_valid_public_dataset_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir()
            make_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(0, self.run_public(root, output))
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("PASS", report["status"])
            self.assertEqual("eval-dataset-quality/v1", report["schema"])
            self.assertFalse(report["labelHumanVerified"])
            self.assertEqual("NOT_REQUIRED_FOR_FIXTURE", report["humanReview"]["status"])
            self.assertEqual("NOT_APPLICABLE", report["leakage"]["status"])

    def test_cross_split_duplicate_and_unclosed_reference_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            rows = [json.loads(line) for line in (root / "cases.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[0]["split"] = "dev"
            rows[1]["split"] = "test"; rows[1]["query"] = "  WHEN do atlas entries EXPIRE?  "; rows[1]["confusingChunkIds"] = [999]
            write_jsonl(root / "cases.jsonl", rows); refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            findings = {item["code"] for item in json.loads(output.read_text(encoding="utf-8"))["findings"]}
            self.assertIn("CROSS_SPLIT_QUERY_OVERLAP", findings)
            self.assertIn("EVIDENCE_REFERENCE_UNKNOWN", findings)

    def test_checksum_tamper_and_public_provenance_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            with (root / "corpus.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(" \n")
            (root / "SOURCES_AND_LICENSE.md").unlink()
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            findings = {item["code"] for item in json.loads(output.read_text(encoding="utf-8"))["findings"]}
            self.assertIn("MANIFEST_FILE_SHA256_MISMATCH", findings)
            self.assertIn("PUBLIC_PROVENANCE_DECLARATION_MISSING", findings)

    def test_private_report_is_aggregate_only_and_human_review_is_unconfirmed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "private"; root.mkdir(); make_private(root)
            output = Path(tmp) / "report.json"
            code = quality.main(["--profile", "reviewed-local-v1", "--dataset-dir", str(root), "--output", str(output)])
            self.assertEqual(2, code)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("REVIEW_REQUIRED", report["status"])
            self.assertTrue(report["aggregateOnly"]); self.assertFalse(report["rawSamplesIncluded"])
            self.assertEqual(0, report["humanReview"]["confirmedHumanReviewCount"])
            serialized = output.read_text(encoding="utf-8")
            for forbidden in ("What is alpha?", "obsidian:fixture", "ChatGPT", "source_path", '"query"'):
                self.assertNotIn(forbidden, serialized)

    def test_credential_value_is_never_written(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            sentinel = "sk-THIS_VALUE_MUST_NEVER_APPEAR_12345"
            rows = [json.loads(line) for line in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[0]["content"] += " api_key=" + sentinel
            write_jsonl(root / "corpus.jsonl", rows); refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            serialized = output.read_text(encoding="utf-8")
            self.assertNotIn(sentinel, serialized)
            self.assertIn("CREDENTIAL_SUSPECTED", serialized)

    def test_existing_output_is_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            output = Path(tmp) / "report.json"; original = b"original-evidence\x00"
            output.write_bytes(original)
            self.assertEqual(1, self.run_public(root, output))
            self.assertEqual(original, output.read_bytes())

    def test_path_escape_and_malformed_input_are_operational_errors(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            escaped = base / "escaped"; escaped.mkdir(); make_public(escaped)
            manifest = json.loads((escaped / "manifest.json").read_text(encoding="utf-8"))
            manifest["files"]["corpus"]["file"] = "../outside.jsonl"
            (escaped / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            output1 = base / "escape-report.json"
            self.assertEqual(1, self.run_public(escaped, output1)); self.assertFalse(output1.exists())

            malformed = base / "malformed"; malformed.mkdir(); make_public(malformed)
            (malformed / "corpus.jsonl").write_text("{not-json}\n", encoding="utf-8")
            output2 = base / "malformed-report.json"
            self.assertEqual(1, self.run_public(malformed, output2)); self.assertFalse(output2.exists())

    def test_exact_human_reviewer_declaration_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "private"; root.mkdir(); make_private(root)
            output = Path(tmp) / "report.json"
            code = quality.main(["--profile", "reviewed-local-v1", "--dataset-dir", str(root), "--output", str(output), "--human-reviewer", "ChatGPT"])
            self.assertEqual(2, code)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(2, report["humanReview"]["confirmedHumanReviewCount"])
            self.assertTrue(report["labelHumanVerified"])

    def test_unanswerable_positive_evidence_is_a_hard_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            rows = [json.loads(line) for line in (root / "cases.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[1]["relevantChunkIds"] = [2]; rows[1]["acceptableChunkIds"] = [2]
            write_jsonl(root / "cases.jsonl", rows); refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            self.assertIn("UNANSWERABLE_POSITIVE_EVIDENCE_PRESENT", output.read_text(encoding="utf-8"))

    def test_duplicate_business_key_is_review_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "private"; root.mkdir(); make_private(root)
            rows = [json.loads(line) for line in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[1]["chunk_business_key"] = rows[0]["chunk_business_key"]
            write_jsonl(root / "corpus.jsonl", rows)
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest["corpusSha256"] = sha256(root / "corpus.jsonl")
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            output = Path(tmp) / "report.json"
            code = quality.main(["--profile", "reviewed-local-v1", "--dataset-dir", str(root), "--output", str(output)])
            self.assertEqual(2, code)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("REVIEW_REQUIRED", report["status"])
            finding = next(item for item in report["findings"] if item["code"] == "CHUNK_BUSINESS_KEY_DUPLICATE")
            self.assertEqual("REVIEW", finding["severity"])
            self.assertNotIn("CHUNK_BUSINESS_KEY_IDENTITY_CONFLICT", output.read_text(encoding="utf-8"))

    def test_public_source_and_evidence_group_closure_are_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            rows = [json.loads(line) for line in (root / "cases.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[0]["acceptableChunkIds"] = [1, 2]
            rows[0]["requiredSourceIds"] = []
            write_jsonl(root / "cases.jsonl", rows); refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            findings = {item["code"] for item in json.loads(output.read_text(encoding="utf-8"))["findings"]}
            self.assertIn("PUBLIC_EVIDENCE_GROUP_CLOSURE_MISMATCH", findings)
            self.assertIn("PUBLIC_REQUIRED_SOURCE_MISSING", findings)
            self.assertIn("PUBLIC_REQUIRED_SOURCE_CLOSURE_MISMATCH", findings)

    def test_public_embedding_identity_reference_and_dimension_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            rows = [json.loads(line) for line in (root / "embeddings.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[1]["embeddingId"] = rows[0]["embeddingId"]
            rows[1]["chunkId"] = 999
            rows[2]["vector"] = [1.0]
            write_jsonl(root / "embeddings.jsonl", rows); refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            findings = {item["code"] for item in json.loads(output.read_text(encoding="utf-8"))["findings"]}
            self.assertTrue({"EMBEDDING_ID_DUPLICATE", "EMBEDDING_REFERENCE_UNKNOWN", "EMBEDDING_VECTOR_DIMENSION_MISMATCH"}.issubset(findings))

    def test_private_cross_split_query_overlap_count_is_not_doubled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "private"; root.mkdir(); make_private(root)
            full_path = root / "standard_reviewed_100_retrieval.jsonl"
            rows = [json.loads(line) for line in full_path.read_text(encoding="utf-8").splitlines()]
            rows[1]["question"] = "  WHAT is alpha?  "
            write_jsonl(full_path, rows)
            write_jsonl(root / "standard_reviewed_100_retrieval_test.jsonl", [rows[1]])
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest["retrievalCaseSha256"] = sha256(full_path)
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            output = Path(tmp) / "report.json"
            self.assertEqual(2, quality.main(["--profile", "reviewed-local-v1", "--dataset-dir", str(root), "--output", str(output)]))
            findings = json.loads(output.read_text(encoding="utf-8"))["findings"]
            overlap = next(item for item in findings if item["code"] == "CROSS_SPLIT_QUERY_OVERLAP")
            self.assertEqual(1, overlap["count"])

    def test_public_duplicate_content_and_embedding_coverage_mismatch_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dataset"; root.mkdir(); make_public(root)
            corpus = [json.loads(line) for line in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines()]
            corpus[1]["content"] = "  " + corpus[0]["content"].upper() + "  "
            write_jsonl(root / "corpus.jsonl", corpus)
            embeddings = [json.loads(line) for line in (root / "embeddings.jsonl").read_text(encoding="utf-8").splitlines()]
            embeddings[1]["chunkId"] = embeddings[0]["chunkId"]
            write_jsonl(root / "embeddings.jsonl", embeddings)
            refresh_public(root)
            output = Path(tmp) / "report.json"
            self.assertEqual(2, self.run_public(root, output))
            findings = {item["code"] for item in json.loads(output.read_text(encoding="utf-8"))["findings"]}
            self.assertIn("PUBLIC_NORMALIZED_CORPUS_CONTENT_DUPLICATE", findings)
            self.assertIn("EMBEDDING_REFERENCE_COVERAGE_MISMATCH", findings)


if __name__ == "__main__":
    unittest.main()
