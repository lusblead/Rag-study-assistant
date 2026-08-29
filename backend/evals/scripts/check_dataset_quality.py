#!/usr/bin/env python3
"""Deterministic, aggregate-only quality gates for repository evaluation data."""

from __future__ import annotations

import argparse
import collections
import dataclasses
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any, Iterable, Sequence


SCHEMA_NAME = "eval-dataset-quality/v1"
PUBLIC_PROFILE = "public-small-v1"
PRIVATE_PROFILE = "reviewed-local-v1"
SUPPORTED_PROFILES = (PUBLIC_PROFILE, PRIVATE_PROFILE)


class OperationalError(Exception):
    """An input/output condition for which no quality report may be trusted."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise OperationalError(message)


@dataclasses.dataclass(frozen=True)
class Config:
    profile: str
    dataset_dir: Path
    output: Path
    human_reviewers: tuple[str, ...]


@dataclasses.dataclass(frozen=True)
class FileFact:
    name: str
    sha256: str
    records: int | None


@dataclasses.dataclass
class Inspection:
    config: Config
    manifest: Any
    manifest_exists: bool
    corpus: list[Any] | None
    cases: list[Any] | None
    dev_cases: list[Any] | None
    test_cases: list[Any] | None
    embeddings: list[Any] | None
    checksums: dict[str, str] | None
    source_declaration_exists: bool
    source_declaration: str
    file_facts: dict[str, FileFact]
    expected_names: set[str]
    privacy_counts: collections.Counter[str]
    source_kind_counts: collections.Counter[str]


@dataclasses.dataclass(frozen=True)
class Finding:
    code: str
    severity: str
    count: int


class FindingBag:
    def __init__(self) -> None:
        self._counts: collections.Counter[tuple[str, str]] = collections.Counter()

    def add(self, code: str, severity: str = "ERROR", count: int = 1) -> None:
        if count > 0:
            self._counts[(code, severity)] += count

    def findings(self) -> list[Finding]:
        return [
            Finding(code, severity, count)
            for (code, severity), count in sorted(self._counts.items())
        ]


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise OperationalError("input read failed") from exc
    return digest.hexdigest()


def _within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _member(dataset_dir: Path, name: Any) -> Path:
    if not _nonempty_string(name):
        raise OperationalError("declared dataset member is not a filename")
    relative = Path(name)
    if relative.is_absolute():
        raise OperationalError("declared dataset member escapes dataset directory")
    resolved = (dataset_dir / relative).resolve()
    if not _within(resolved, dataset_dir):
        raise OperationalError("declared dataset member escapes dataset directory")
    return resolved


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise OperationalError("JSON read or parse failed") from exc


def _read_jsonl(path: Path) -> list[Any]:
    rows: list[Any] = []
    try:
        with path.open(encoding="utf-8-sig") as stream:
            for line in stream:
                if line.strip():
                    rows.append(json.loads(line))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise OperationalError("JSONL read or parse failed") from exc
    return rows


def _record_file(path: Path, rows: list[Any] | None = None) -> FileFact:
    return FileFact(path.name, _sha256(path), None if rows is None else len(rows))


def _load_optional_jsonl(
    path: Path, facts: dict[str, FileFact]
) -> list[Any] | None:
    if not path.is_file():
        return None
    rows = _read_jsonl(path)
    facts[path.name] = _record_file(path, rows)
    return rows


def _parse_checksums(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except (OSError, UnicodeError) as exc:
        raise OperationalError("checksum declaration read failed") from exc
    for line in lines:
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?([^\s]+)", line.strip())
        if not match:
            values["__INVALID__"] = ""
            continue
        values[Path(match.group(2)).name] = match.group(1).lower()
    return values


def _walk_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _walk_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _walk_strings(item)


_CREDENTIAL_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\b(?:sk|rk|pk)-[A-Za-z0-9_-]{16,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(
        r"(?i)\b(?:api[_ -]?key|access[_ -]?token|password|client[_ -]?secret)"
        r"\s*[:=]\s*['\"]?[A-Za-z0-9_./+=-]{12,}"
    ),
)
_ABSOLUTE_PATH_PATTERNS = (
    re.compile(r"(?i)(?:^|\s)[A-Z]:\\[^\s]+"),
    re.compile(r"(?:^|\s)/(?:Users|home|mnt/[a-z])/[^\s]+"),
)
_EMAIL_PATTERN = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_PHONE_PATTERN = re.compile(r"(?<!\d)(?:\+?\d[\s().-]*){8,15}(?!\d)")
_PRIVATE_MARKER_PATTERN = re.compile(
    r"(?i)(?:obsidian:|private://|\bconfidential\b|\binternal-only\b|私密)"
)


def _privacy_counts(values: Iterable[Any]) -> collections.Counter[str]:
    counts: collections.Counter[str] = collections.Counter()
    for value in values:
        for text in _walk_strings(value):
            if any(pattern.search(text) for pattern in _CREDENTIAL_PATTERNS):
                counts["credential"] += 1
            if any(pattern.search(text) for pattern in _ABSOLUTE_PATH_PATTERNS):
                counts["absolutePath"] += 1
            if _EMAIL_PATTERN.search(text):
                counts["email"] += 1
            if _PHONE_PATTERN.search(text):
                counts["phone"] += 1
            if _PRIVATE_MARKER_PATTERN.search(text):
                counts["privateMarker"] += 1
    return counts


def parse_config(argv: Sequence[str] | None = None) -> Config:
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, choices=SUPPORTED_PROFILES)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--human-reviewer", action="append", default=[])
    args = parser.parse_args(argv)
    dataset_dir = Path(args.dataset_dir).resolve()
    output = Path(args.output).resolve()
    if not dataset_dir.is_dir():
        raise OperationalError("dataset directory is unavailable")
    if output.exists():
        raise OperationalError("output already exists")
    if not output.parent.is_dir():
        raise OperationalError("output parent directory is unavailable")
    if _within(output, dataset_dir):
        raise OperationalError("output must be outside the read-only dataset directory")
    reviewers = tuple(args.human_reviewer)
    if any(not _nonempty_string(value) for value in reviewers):
        raise OperationalError("human reviewer declaration must be non-empty")
    return Config(args.profile, dataset_dir, output, reviewers)


def inspect_dataset(config: Config) -> Inspection:
    root = config.dataset_dir
    facts: dict[str, FileFact] = {}
    manifest_path = root / "manifest.json"
    manifest_exists = manifest_path.is_file()
    manifest = _read_json(manifest_path) if manifest_exists else None
    if manifest_exists:
        facts[manifest_path.name] = _record_file(manifest_path)

    corpus_path = root / "corpus.jsonl"
    cases_path: Path
    dev_path: Path | None = None
    test_path: Path | None = None
    embeddings_path: Path | None = None
    checksums: dict[str, str] | None = None
    source_declaration_exists = False
    source_declaration = ""
    expected_names = {"manifest.json", "corpus.jsonl"}

    if config.profile == PUBLIC_PROFILE:
        files = manifest.get("files") if isinstance(manifest, dict) else None
        corpus_name = (
            files.get("corpus", {}).get("file")
            if isinstance(files, dict) and isinstance(files.get("corpus"), dict)
            else "corpus.jsonl"
        )
        cases_name = (
            files.get("cases", {}).get("file")
            if isinstance(files, dict) and isinstance(files.get("cases"), dict)
            else "cases.jsonl"
        )
        embeddings_name = (
            files.get("embeddings", {}).get("file")
            if isinstance(files, dict) and isinstance(files.get("embeddings"), dict)
            else "embeddings.jsonl"
        )
        corpus_path = _member(root, corpus_name)
        cases_path = _member(root, cases_name)
        embeddings_path = _member(root, embeddings_name)
        expected_names.update(
            {corpus_path.name, cases_path.name, embeddings_path.name,
             "checksums.sha256", "SOURCES_AND_LICENSE.md"}
        )
        checksum_path = root / "checksums.sha256"
        if checksum_path.is_file():
            checksums = _parse_checksums(checksum_path)
            facts[checksum_path.name] = _record_file(checksum_path)
        source_path = root / "SOURCES_AND_LICENSE.md"
        source_declaration_exists = source_path.is_file()
        if source_declaration_exists:
            try:
                source_declaration = source_path.read_text(encoding="utf-8-sig")
            except (OSError, UnicodeError) as exc:
                raise OperationalError("source declaration read failed") from exc
            facts[source_path.name] = _record_file(source_path)
    else:
        case_name = (
            manifest.get("retrievalCaseFile")
            if isinstance(manifest, dict)
            else "standard_reviewed_100_retrieval.jsonl"
        )
        cases_path = _member(root, case_name)
        dev_path = root / "standard_reviewed_100_retrieval_dev.jsonl"
        test_path = root / "standard_reviewed_100_retrieval_test.jsonl"
        expected_names.update({cases_path.name, dev_path.name, test_path.name})

    corpus = _load_optional_jsonl(corpus_path, facts)
    cases = _load_optional_jsonl(cases_path, facts)
    embeddings = (
        _load_optional_jsonl(embeddings_path, facts) if embeddings_path else None
    )
    dev_cases = _load_optional_jsonl(dev_path, facts) if dev_path else None
    test_cases = _load_optional_jsonl(test_path, facts) if test_path else None
    scanned = [
        rows for rows in (corpus, cases, dev_cases, test_cases, embeddings)
        if rows is not None
    ]
    privacy = _privacy_counts(scanned)
    source_kinds: collections.Counter[str] = collections.Counter()
    if corpus:
        for row in corpus:
            if isinstance(row, dict) and _nonempty_string(row.get("source_kind")):
                source_kinds[row["source_kind"]] += 1
    return Inspection(
        config, manifest, manifest_exists, corpus, cases, dev_cases, test_cases,
        embeddings, checksums, source_declaration_exists, source_declaration,
        facts, expected_names, privacy, source_kinds,
    )


def _require_object_rows(rows: list[Any] | None, code: str, bag: FindingBag) -> list[dict]:
    if rows is None:
        bag.add(code)
        return []
    bad = sum(not isinstance(row, dict) for row in rows)
    bag.add("JSONL_ROW_NOT_OBJECT", count=bad)
    return [row for row in rows if isinstance(row, dict)]


def _required_fields(
    rows: Iterable[dict], requirements: dict[str, Any], bag: FindingBag
) -> None:
    for row in rows:
        for field, predicate in requirements.items():
            if not predicate(row.get(field)):
                bag.add("SCHEMA_REQUIRED_FIELD_INVALID")


def _duplicates(values: Iterable[Any]) -> int:
    counter = collections.Counter(values)
    return sum(count - 1 for count in counter.values() if count > 1)


def _normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _list_of_ids(value: Any) -> list[Any] | None:
    if not isinstance(value, list):
        return None
    if any(not isinstance(item, (str, int)) or isinstance(item, bool) for item in value):
        return None
    return value


def _group_ids(value: Any, camel: bool) -> list[Any] | None:
    if not isinstance(value, list):
        return None
    result: list[Any] = []
    key = "chunkIds" if camel else "chunk_ids"
    for group in value:
        if not isinstance(group, dict):
            return None
        ids = _list_of_ids(group.get(key))
        if ids is None or not ids:
            return None
        result.extend(ids)
    return result


def _case_semantics(
    rows: list[dict], corpus_ids: set[Any], public: bool, bag: FindingBag,
    corpus_sources: dict[Any, str] | None = None,
) -> None:
    id_key = "caseId" if public else "id"
    query_key = "query" if public else "question"
    relevant_key = "relevantChunkIds" if public else "relevant_chunk_ids"
    acceptable_key = "acceptableChunkIds" if public else "acceptable_chunk_ids"
    groups_key = "requiredEvidenceGroups" if public else "required_evidence_groups"
    confusing_key = "confusingChunkIds" if public else "confusing_chunks"
    _required_fields(
        rows,
        {
            id_key: _nonempty_string,
            query_key: _nonempty_string,
            "answerable": lambda v: isinstance(v, bool),
            relevant_key: lambda v: _list_of_ids(v) is not None,
            acceptable_key: lambda v: _list_of_ids(v) is not None,
            groups_key: lambda v: _group_ids(v, public) is not None,
            **({"requiredSourceIds": lambda v: isinstance(v, list) and all(_nonempty_string(item) for item in v)} if public else {}),
        },
        bag,
    )
    identities = [row[id_key] for row in rows if _nonempty_string(row.get(id_key))]
    bag.add("CASE_ID_DUPLICATE", count=_duplicates(identities))
    queries: list[str] = []
    split_queries: dict[str, set[str]] = collections.defaultdict(set)
    for row in rows:
        query = row.get(query_key)
        if _nonempty_string(query):
            normalized = _normalize(query)
            queries.append(normalized)
            if row.get("split") in ("dev", "test"):
                split_queries[row["split"]].add(normalized)
        relevant = _list_of_ids(row.get(relevant_key))
        acceptable = _list_of_ids(row.get(acceptable_key))
        groups = _group_ids(row.get(groups_key), public)
        if relevant is None or acceptable is None or groups is None:
            continue
        confusing: list[Any] = []
        raw_confusing = row.get(confusing_key, [])
        if public:
            parsed = _list_of_ids(raw_confusing)
            if parsed is None:
                bag.add("SCHEMA_REQUIRED_FIELD_INVALID")
                continue
            confusing = parsed
        elif isinstance(raw_confusing, list):
            for item in raw_confusing:
                if not isinstance(item, dict) or not isinstance(item.get("chunk_id"), str):
                    bag.add("SCHEMA_REQUIRED_FIELD_INVALID")
                else:
                    confusing.append(item["chunk_id"])
        else:
            bag.add("SCHEMA_REQUIRED_FIELD_INVALID")
        evidence_ids: list[Any] = []
        if not public:
            evidence = row.get("evidence")
            if not isinstance(evidence, list):
                bag.add("SCHEMA_REQUIRED_FIELD_INVALID")
            else:
                for item in evidence:
                    if not isinstance(item, dict) or not isinstance(item.get("chunk_id"), str):
                        bag.add("SCHEMA_REQUIRED_FIELD_INVALID")
                    else:
                        evidence_ids.append(item["chunk_id"])
        positive = set(relevant) | set(acceptable) | set(groups) | set(evidence_ids)
        if row.get("answerable") is True and (
            not relevant or not acceptable or not groups or (not public and not evidence_ids)
        ):
            bag.add("ANSWERABLE_POSITIVE_EVIDENCE_MISSING")
        if row.get("answerable") is False and positive:
            bag.add("UNANSWERABLE_POSITIVE_EVIDENCE_PRESENT")
        if set(relevant) - set(acceptable) or set(groups) - set(acceptable):
            bag.add("EVIDENCE_SET_CONTRADICTION")
        if public and set(groups) != set(acceptable):
            bag.add("PUBLIC_EVIDENCE_GROUP_CLOSURE_MISMATCH")
        if public:
            required_sources = row.get("requiredSourceIds")
            if not isinstance(required_sources, list) or any(not _nonempty_string(item) for item in required_sources):
                required_sources = []
            acceptable_sources = {
                corpus_sources[chunk_id]
                for chunk_id in acceptable
                if corpus_sources is not None and chunk_id in corpus_sources
            }
            if row.get("answerable") is True and not required_sources:
                bag.add("PUBLIC_REQUIRED_SOURCE_MISSING")
            if set(required_sources) != acceptable_sources:
                bag.add("PUBLIC_REQUIRED_SOURCE_CLOSURE_MISMATCH")
        if set(confusing) & positive:
            bag.add("EVIDENCE_SET_CONTRADICTION")
        unknown = (positive | set(confusing)) - corpus_ids
        bag.add("EVIDENCE_REFERENCE_UNKNOWN", count=len(unknown))
    bag.add("NORMALIZED_QUERY_DUPLICATE", count=_duplicates(queries))
    overlap = split_queries.get("dev", set()) & split_queries.get("test", set())
    bag.add("CROSS_SPLIT_QUERY_OVERLAP", count=len(overlap))


def _manifest_schema(inspection: Inspection, bag: FindingBag) -> dict:
    if not inspection.manifest_exists:
        bag.add("MANIFEST_MISSING")
        return {}
    if not isinstance(inspection.manifest, dict):
        bag.add("MANIFEST_SCHEMA_INVALID")
        return {}
    manifest = inspection.manifest
    if not _is_int(manifest.get("schemaVersion")) or manifest["schemaVersion"] != 1:
        bag.add("MANIFEST_SCHEMA_VERSION_INVALID")
    return manifest


def _evaluate_public(inspection: Inspection, manifest: dict, bag: FindingBag) -> None:
    corpus = _require_object_rows(inspection.corpus, "CORPUS_FILE_MISSING", bag)
    cases = _require_object_rows(inspection.cases, "CASE_FILE_MISSING", bag)
    embeddings = _require_object_rows(
        inspection.embeddings, "EMBEDDING_FILE_MISSING", bag
    )
    for field, predicate in {
        "expectedChunkCount": _is_int,
        "expectedCaseCount": _is_int,
        "sourceType": _nonempty_string,
        "license": _nonempty_string,
    }.items():
        if not predicate(manifest.get(field)):
            bag.add("MANIFEST_SCHEMA_INVALID")
    files = manifest.get("files")
    if not isinstance(files, dict):
        bag.add("MANIFEST_SCHEMA_INVALID")
        files = {}
    for key in ("corpus", "cases", "embeddings"):
        declaration = files.get(key)
        if not isinstance(declaration, dict):
            bag.add("MANIFEST_FILE_DECLARATION_INVALID")
            continue
        if not _nonempty_string(declaration.get("file")) or not _is_int(declaration.get("records")) or not re.fullmatch(r"[0-9a-fA-F]{64}", str(declaration.get("sha256", ""))):
            bag.add("MANIFEST_FILE_DECLARATION_INVALID")
            continue
        fact = inspection.file_facts.get(Path(declaration["file"]).name)
        if fact is None:
            bag.add("DECLARED_FILE_MISSING")
        else:
            if fact.sha256.lower() != declaration["sha256"].lower():
                bag.add("MANIFEST_FILE_SHA256_MISMATCH")
            if fact.records != declaration["records"]:
                bag.add("MANIFEST_FILE_RECORD_COUNT_MISMATCH")
    if len(corpus) != manifest.get("expectedChunkCount"):
        bag.add("EXPECTED_CHUNK_COUNT_MISMATCH")
    if len(cases) != manifest.get("expectedCaseCount"):
        bag.add("EXPECTED_CASE_COUNT_MISMATCH")
    if inspection.checksums is None:
        bag.add("CHECKSUM_DECLARATION_MISSING")
    else:
        if "__INVALID__" in inspection.checksums:
            bag.add("CHECKSUM_DECLARATION_INVALID")
        for name in {"manifest.json"} | {
            fact.name for fact in inspection.file_facts.values()
            if fact.name in {"corpus.jsonl", "cases.jsonl", "embeddings.jsonl"}
        }:
            fact = inspection.file_facts.get(name)
            if fact is None or inspection.checksums.get(name) != fact.sha256:
                bag.add("CHECKSUM_FILE_MISMATCH")
    if not inspection.source_declaration_exists:
        bag.add("PUBLIC_PROVENANCE_DECLARATION_MISSING")
    corpus_ids: list[Any] = []
    _required_fields(
        corpus,
        {
            "chunkId": _is_int,
            "content": _nonempty_string,
            "sourceId": _nonempty_string,
            "license": _nonempty_string,
        },
        bag,
    )
    for row in corpus:
        if _is_int(row.get("chunkId")):
            corpus_ids.append(row["chunkId"])
        if manifest.get("license") != row.get("license"):
            bag.add("PUBLIC_LICENSE_MISMATCH")
        source_id = row.get("sourceId")
        if _nonempty_string(source_id) and source_id not in inspection.source_declaration:
            bag.add("PUBLIC_SOURCE_ID_UNDECLARED")
    normalized_content = [
        _normalize(row["content"])
        for row in corpus
        if _nonempty_string(row.get("content"))
    ]
    bag.add(
        "PUBLIC_NORMALIZED_CORPUS_CONTENT_DUPLICATE",
        count=_duplicates(normalized_content),
    )
    if manifest.get("sourceType") != "synthetic-original":
        bag.add("PUBLIC_SOURCE_TYPE_INVALID")
    if _nonempty_string(manifest.get("license")) and manifest["license"] not in inspection.source_declaration:
        bag.add("PUBLIC_LICENSE_UNDECLARED")
    bag.add("CORPUS_CHUNK_ID_DUPLICATE", count=_duplicates(corpus_ids))
    corpus_sources = {
        row["chunkId"]: row["sourceId"]
        for row in corpus
        if _is_int(row.get("chunkId")) and _nonempty_string(row.get("sourceId"))
    }
    _case_semantics(cases, set(corpus_ids), True, bag, corpus_sources)
    if not _is_int(manifest.get("embeddingDimension")) or manifest.get("embeddingDimension", 0) <= 0:
        bag.add("MANIFEST_EMBEDDING_DIMENSION_INVALID")
    dimension = manifest.get("embeddingDimension") if _is_int(manifest.get("embeddingDimension")) else None
    _required_fields(
        embeddings,
        {
            "embeddingId": _nonempty_string,
            "itemType": lambda v: v in ("chunk", "query"),
            "vector": lambda v: isinstance(v, list) and bool(v) and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v),
        },
        bag,
    )
    embedding_ids = [
        row["embeddingId"] for row in embeddings
        if _nonempty_string(row.get("embeddingId"))
    ]
    case_ids = {
        row["caseId"] for row in cases if _nonempty_string(row.get("caseId"))
    }
    bag.add("EMBEDDING_ID_DUPLICATE", count=_duplicates(embedding_ids))
    chunk_embedding_counts: collections.Counter[Any] = collections.Counter()
    query_embedding_counts: collections.Counter[Any] = collections.Counter()
    for row in embeddings:
        vector = row.get("vector")
        if dimension is not None and isinstance(vector, list) and len(vector) != dimension:
            bag.add("EMBEDDING_VECTOR_DIMENSION_MISMATCH")
        if row.get("itemType") == "chunk":
            if row.get("chunkId") not in set(corpus_ids):
                bag.add("EMBEDDING_REFERENCE_UNKNOWN")
            else:
                chunk_embedding_counts[row.get("chunkId")] += 1
        elif row.get("itemType") == "query":
            if row.get("caseId") not in case_ids:
                bag.add("EMBEDDING_REFERENCE_UNKNOWN")
            else:
                query_embedding_counts[row.get("caseId")] += 1
    coverage_mismatch = sum(
        chunk_embedding_counts[chunk_id] != 1 for chunk_id in set(corpus_ids)
    ) + sum(query_embedding_counts[case_id] != 1 for case_id in case_ids)
    bag.add("EMBEDDING_REFERENCE_COVERAGE_MISMATCH", count=coverage_mismatch)
    for category, code in {
        "absolutePath": "PUBLIC_ABSOLUTE_PATH",
        "email": "PUBLIC_EMAIL",
        "phone": "PUBLIC_PHONE",
        "privateMarker": "PUBLIC_PRIVATE_MARKER",
    }.items():
        bag.add(code, count=inspection.privacy_counts[category])


def _canonical_row(row: dict) -> str:
    return json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _evaluate_private(inspection: Inspection, manifest: dict, bag: FindingBag) -> None:
    corpus = _require_object_rows(inspection.corpus, "CORPUS_FILE_MISSING", bag)
    cases = _require_object_rows(inspection.cases, "CASE_FILE_MISSING", bag)
    dev = _require_object_rows(inspection.dev_cases, "DEV_CASE_FILE_MISSING", bag)
    test = _require_object_rows(inspection.test_cases, "TEST_CASE_FILE_MISSING", bag)
    for field, predicate in {
        "corpusSha256": lambda v: bool(re.fullmatch(r"[0-9a-fA-F]{64}", str(v))),
        "retrievalCaseFile": _nonempty_string,
        "retrievalCaseSha256": lambda v: bool(re.fullmatch(r"[0-9a-fA-F]{64}", str(v))),
        "expectedChunkCount": _is_int,
        "expectedCaseCount": _is_int,
        "splitCounts": lambda v: isinstance(v, dict) and _is_int(v.get("dev")) and _is_int(v.get("test")),
    }.items():
        if not predicate(manifest.get(field)):
            bag.add("MANIFEST_SCHEMA_INVALID")
    corpus_fact = inspection.file_facts.get("corpus.jsonl")
    case_name = Path(str(manifest.get("retrievalCaseFile", ""))).name
    case_fact = inspection.file_facts.get(case_name)
    if corpus_fact and corpus_fact.sha256 != str(manifest.get("corpusSha256", "")).lower():
        bag.add("MANIFEST_FILE_SHA256_MISMATCH")
    if case_fact and case_fact.sha256 != str(manifest.get("retrievalCaseSha256", "")).lower():
        bag.add("MANIFEST_FILE_SHA256_MISMATCH")
    if len(corpus) != manifest.get("expectedChunkCount"):
        bag.add("EXPECTED_CHUNK_COUNT_MISMATCH")
    if len(cases) != manifest.get("expectedCaseCount"):
        bag.add("EXPECTED_CASE_COUNT_MISMATCH")
    split_counts = manifest.get("splitCounts", {})
    if len(dev) != split_counts.get("dev") or len(test) != split_counts.get("test"):
        bag.add("SPLIT_COUNT_MISMATCH")
    _required_fields(
        corpus,
        {
            "chunk_id": _nonempty_string,
            "chunk_business_key": _nonempty_string,
            "content": _nonempty_string,
            "source_document": _nonempty_string,
            "source_path": _nonempty_string,
            "source_kind": _nonempty_string,
        },
        bag,
    )
    chunk_ids = [row["chunk_id"] for row in corpus if _nonempty_string(row.get("chunk_id"))]
    business_keys = [row["chunk_business_key"] for row in corpus if _nonempty_string(row.get("chunk_business_key"))]
    bag.add("CORPUS_CHUNK_ID_DUPLICATE", count=_duplicates(chunk_ids))
    requirements = {
        "case_key": _nonempty_string,
        "split": lambda v: v in ("dev", "test"),
        "evidence_signature": _nonempty_string,
        "review_status": lambda v: isinstance(v, str),
        "reviewer": lambda v: isinstance(v, str),
        "human_verdict": lambda v: isinstance(v, str),
        "source_document": _nonempty_string,
    }
    _required_fields(cases, requirements, bag)
    _case_semantics(cases, set(chunk_ids), False, bag)
    case_keys = [row["case_key"] for row in cases if _nonempty_string(row.get("case_key"))]
    bag.add("CASE_KEY_DUPLICATE", count=_duplicates(case_keys))
    for split_name, rows in (("dev", dev), ("test", test)):
        _required_fields(rows, requirements, bag)
        bag.add("SPLIT_FIELD_MISMATCH", count=sum(row.get("split") != split_name for row in rows))
    dev_ids = {row.get("id") for row in dev if _nonempty_string(row.get("id"))}
    test_ids = {row.get("id") for row in test if _nonempty_string(row.get("id"))}
    dev_keys = {row.get("case_key") for row in dev if _nonempty_string(row.get("case_key"))}
    test_keys = {row.get("case_key") for row in test if _nonempty_string(row.get("case_key"))}
    bag.add("CROSS_SPLIT_CASE_ID_DUPLICATE", count=len(dev_ids & test_ids))
    bag.add("CROSS_SPLIT_CASE_KEY_DUPLICATE", count=len(dev_keys & test_keys))
    full_by_id = {row.get("id"): row for row in cases if _nonempty_string(row.get("id"))}
    split_rows = dev + test
    split_by_id = {row.get("id"): row for row in split_rows if _nonempty_string(row.get("id"))}
    if set(full_by_id) != set(split_by_id):
        bag.add("SPLIT_FULL_MEMBERSHIP_MISMATCH")
    canonical_mismatch = sum(
        key in split_by_id and _canonical_row(row) != _canonical_row(split_by_id[key])
        for key, row in full_by_id.items()
    )
    bag.add("SPLIT_CANONICAL_ROW_MISMATCH", count=canonical_mismatch)


def evaluate_hard_gates(inspection: Inspection) -> list[Finding]:
    bag = FindingBag()
    manifest = _manifest_schema(inspection, bag)
    if inspection.config.profile == PUBLIC_PROFILE:
        _evaluate_public(inspection, manifest, bag)
    else:
        _evaluate_private(inspection, manifest, bag)
    bag.add("CREDENTIAL_SUSPECTED", count=inspection.privacy_counts["credential"])
    return bag.findings()


def evaluate_review_gates(inspection: Inspection) -> list[Finding]:
    bag = FindingBag()
    if inspection.config.profile == PUBLIC_PROFILE:
        return []
    cases = [row for row in (inspection.cases or []) if isinstance(row, dict)]
    declared = set(inspection.config.human_reviewers)
    confirmed = sum(
        row.get("reviewer") in declared
        and _nonempty_string(row.get("review_status"))
        and _nonempty_string(row.get("human_verdict"))
        for row in cases
    )
    bag.add("HUMAN_REVIEW_UNCONFIRMED", "REVIEW", len(cases) - confirmed)
    manifest = inspection.manifest if isinstance(inspection.manifest, dict) else {}
    if not any(_nonempty_string(manifest.get(key)) for key in ("license", "licenseId")):
        bag.add("LICENSE_UNCONFIRMED", "REVIEW")
    private_count = sum(
        inspection.privacy_counts[key]
        for key in ("absolutePath", "email", "phone", "privateMarker")
    )
    bag.add("EXPECTED_PRIVATE_DATA", "REVIEW", private_count)
    signatures = [
        row["evidence_signature"] for row in cases
        if _nonempty_string(row.get("evidence_signature"))
    ]
    bag.add("EVIDENCE_SIGNATURE_DUPLICATE", "REVIEW", _duplicates(signatures))
    dev = [row for row in (inspection.dev_cases or []) if isinstance(row, dict)]
    test = [row for row in (inspection.test_cases or []) if isinstance(row, dict)]
    dev_sig = {row["evidence_signature"] for row in dev if _nonempty_string(row.get("evidence_signature"))}
    test_sig = {row["evidence_signature"] for row in test if _nonempty_string(row.get("evidence_signature"))}
    bag.add("CROSS_SPLIT_EVIDENCE_SIGNATURE_OVERLAP", "REVIEW", len(dev_sig & test_sig))
    corpus = [row for row in (inspection.corpus or []) if isinstance(row, dict)]
    normalized_content = [
        _normalize(row["content"]) for row in corpus if _nonempty_string(row.get("content"))
    ]
    business_keys = [
        row["chunk_business_key"] for row in corpus
        if _nonempty_string(row.get("chunk_business_key"))
    ]
    bag.add("CHUNK_BUSINESS_KEY_DUPLICATE", "REVIEW", _duplicates(business_keys))
    bag.add("NORMALIZED_CORPUS_CONTENT_DUPLICATE", "REVIEW", _duplicates(normalized_content))
    return bag.findings()


def _ratio(count: int, total: int) -> float | None:
    return None if total == 0 else round(count / total, 6)


def _aggregate_file_hash(facts: Iterable[FileFact]) -> str:
    digest = hashlib.sha256(b"eval-dataset-quality-files-v1\0")
    for fact in sorted(facts, key=lambda item: item.name):
        digest.update(fact.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(fact.sha256))
    return digest.hexdigest()


def build_aggregate_report(
    inspection: Inspection,
    hard_findings: Sequence[Finding],
    review_findings: Sequence[Finding],
) -> dict[str, Any]:
    status = "FAIL" if hard_findings else "REVIEW_REQUIRED" if review_findings else "PASS"
    cases = [row for row in (inspection.cases or []) if isinstance(row, dict)]
    corpus = [row for row in (inspection.corpus or []) if isinstance(row, dict)]
    declared = set(inspection.config.human_reviewers)
    review_status_count = sum(_nonempty_string(row.get("review_status")) for row in cases)
    reviewer_count = sum(_nonempty_string(row.get("reviewer")) for row in cases)
    verdict_count = sum(_nonempty_string(row.get("human_verdict")) for row in cases)
    confirmed_count = sum(
        row.get("reviewer") in declared
        and _nonempty_string(row.get("review_status"))
        and _nonempty_string(row.get("human_verdict"))
        for row in cases
    )
    split_counts = collections.Counter(
        row.get("split") for row in cases if row.get("split") in ("dev", "test")
    )
    findings = sorted(
        [*hard_findings, *review_findings], key=lambda item: (item.severity, item.code)
    )
    return {
        "schema": SCHEMA_NAME,
        "aggregateOnly": True,
        "rawSamplesIncluded": False,
        "profile": inspection.config.profile,
        "status": status,
        "businessLabelQualityValidated": False,
        "labelHumanVerified": bool(cases) and confirmed_count == len(cases),
        "humanReview": {
            "status": "NOT_REQUIRED_FOR_FIXTURE" if inspection.config.profile == PUBLIC_PROFILE else ("CONFIRMED" if confirmed_count == len(cases) and cases else "UNCONFIRMED"),
            "totalCases": len(cases),
            "reviewStatusNonEmpty": review_status_count,
            "reviewStatusCoverage": _ratio(review_status_count, len(cases)),
            "reviewerNonEmpty": reviewer_count,
            "reviewerCoverage": _ratio(reviewer_count, len(cases)),
            "humanVerdictNonEmpty": verdict_count,
            "humanVerdictCoverage": _ratio(verdict_count, len(cases)),
            "confirmedHumanReviewCount": confirmed_count,
            "confirmedHumanReviewCoverage": _ratio(confirmed_count, len(cases)),
        },
        "leakage": {
            "status": "NOT_APPLICABLE" if not split_counts else "CHECKED",
            "devCount": split_counts.get("dev", 0),
            "testCount": split_counts.get("test", 0),
        },
        "counts": {
            "corpusRecords": len(corpus),
            "caseRecords": len(cases),
            "answerableCases": sum(row.get("answerable") is True for row in cases),
            "unanswerableCases": sum(row.get("answerable") is False for row in cases),
        },
        "sourceKindDistribution": dict(sorted(inspection.source_kind_counts.items())),
        "files": [
            {"basename": fact.name, "sha256": fact.sha256, **({} if fact.records is None else {"records": fact.records})}
            for fact in sorted(inspection.file_facts.values(), key=lambda item: item.name)
        ],
        "aggregateHashes": {
            "datasetFilesSha256": _aggregate_file_hash(inspection.file_facts.values())
        },
        "findings": [dataclasses.asdict(item) for item in findings],
    }


def write_report_exclusive(path: Path, report: dict[str, Any]) -> None:
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    try:
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
    except (FileExistsError, OSError) as exc:
        raise OperationalError("exclusive report creation failed") from exc


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config = parse_config(argv)
        inspection = inspect_dataset(config)
        hard = evaluate_hard_gates(inspection)
        review = evaluate_review_gates(inspection)
        report = build_aggregate_report(inspection, hard, review)
        write_report_exclusive(config.output, report)
        print(f"{report['status']}: {config.output.name}")
        return 0 if report["status"] == "PASS" else 2
    except OperationalError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError) as exc:
        print(f"ERROR: operational failure ({type(exc).__name__})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
