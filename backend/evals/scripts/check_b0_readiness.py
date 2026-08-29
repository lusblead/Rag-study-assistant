#!/usr/bin/env python3
"""Read-only clean-B0 readiness checks. This script never runs B0."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, Mapping, Sequence

TIMEOUT_SECONDS = 8
DEFAULT_DATASET = "backend/evals/datasets/local-obsidian-v3-reviewed"
DEFAULT_CASE = "standard_reviewed_100_retrieval_dev.jsonl"
RUNNERS = (
    "LocalObsidianRetrievalEvalTest.java",
    "RetrievalFixtureSmokeTest.java",
    "PublicRetrievalEvalTest.java",
    "RagRetrievalSmokeEvalTest.java",
)
RUNNER_DIR = Path("backend/src/test/java/com/rag/backend/agent/retrieval/eval")
ENV_NAMES = (
    "RAG_EVAL_EMBEDDING_BASE_URL",
    "RAG_EVAL_EMBEDDING_MODEL",
    "RAG_EVAL_EMBEDDING_API_KEY",
)
DUPLICATE_FORMULA = re.compile(
    r"(?m)\bprivate\s+(?:static\s+)?double\s+"
    r"(?:recallAt\w*|reciprocalRank|ndcgAt\w*|log2)\s*\("
)


class CheckerError(Exception):
    pass


@dataclasses.dataclass(frozen=True)
class CommandResult:
    returncode: int | None
    stdout: bytes = b""
    stderr: bytes = b""
    missing: bool = False
    timed_out: bool = False


Runner = Callable[[Sequence[str], Path, Mapping[str, str] | None, int], CommandResult]


@dataclasses.dataclass(frozen=True)
class Config:
    repo_root: Path
    dataset_dir: Path
    case_file: Path
    output: Path
    java_home: Path | None = None


def resolve_command_name(
    command_name: str,
    platform: str | None = None,
    which: Callable[[str], str | None] = shutil.which,
) -> str:
    """Resolve a command without using a shell; prefer Maven's Windows shim."""
    current_platform = os.name if platform is None else platform
    if current_platform == "nt" and Path(command_name).suffix == "":
        if command_name.lower() == "mvn":
            candidates = (
                command_name + ".cmd",
                command_name + ".exe",
                command_name + ".bat",
                command_name,
            )
        else:
            candidates = (command_name + ".exe", command_name,)
    else:
        candidates = (command_name,)
    for candidate in candidates:
        resolved = which(candidate)
        if resolved:
            return resolved
    return candidates[0]


def run_command(
    command: Sequence[str], cwd: Path, env: Mapping[str, str] | None, timeout: int
) -> CommandResult:
    try:
        completed = subprocess.run(
            list(command), cwd=cwd, env=None if env is None else dict(env),
            capture_output=True, timeout=timeout, check=False,
        )
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)
    except FileNotFoundError:
        return CommandResult(None, missing=True)
    except subprocess.TimeoutExpired as exc:
        return CommandResult(
            None, exc.stdout or b"", exc.stderr or b"", timed_out=True
        )


def _git(runner: Runner, root: Path, *args: str, allowed=(0,)) -> CommandResult:
    result = runner(("git", *args), root, None, TIMEOUT_SECONDS)
    if result.missing or result.timed_out or result.returncode not in allowed:
        raise CheckerError("Git command failed: git " + " ".join(args))
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _untracked_digest(root: Path, raw_paths: bytes) -> tuple[int, str]:
    aggregate = hashlib.sha256(b"b0-untracked-v1\0")
    paths = sorted(item for item in raw_paths.split(b"\0") if item)
    for raw_path in paths:
        relative = Path(os.fsdecode(raw_path))
        target = (root / relative).resolve()
        try:
            target.relative_to(root)
        except ValueError as exc:
            raise CheckerError("Git returned an untracked path outside repo") from exc
        content_hash = _sha256(target)
        aggregate.update(len(raw_path).to_bytes(8, "big"))
        aggregate.update(raw_path)
        aggregate.update(bytes.fromhex(content_hash))
    return len(paths), aggregate.hexdigest()


def _tracked_count(status: bytes) -> int:
    fields = status.split(b"\0")
    count = 0
    index = 0
    while index < len(fields):
        record = fields[index]
        index += 1
        if not record:
            continue
        if len(record) < 3:
            raise CheckerError("Cannot parse git status output")
        code = record[:2]
        if code != b"??":
            count += 1
        if b"R" in code or b"C" in code:
            index += 1
    return count


def collect_git_snapshot(root: Path, runner: Runner = run_command) -> dict:
    actual_root = Path(os.fsdecode(
        _git(runner, root, "rev-parse", "--show-toplevel").stdout.strip()
    )).resolve()
    if actual_root != root.resolve():
        raise CheckerError("--repo-root is not the Git repository root")
    head = _git(runner, root, "rev-parse", "HEAD").stdout.decode().strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", head):
        raise CheckerError("Cannot parse Git HEAD")
    branch_result = _git(
        runner, root, "symbolic-ref", "--quiet", "--short", "HEAD", allowed=(0, 1)
    )
    branch = branch_result.stdout.decode(errors="replace").strip() or "DETACHED"
    status = _git(
        runner, root, "status", "--porcelain=v1", "-z", "--untracked-files=all"
    ).stdout
    untracked_paths = _git(
        runner, root, "ls-files", "--others", "--exclude-standard", "-z"
    ).stdout
    tracked_diff = _git(
        runner, root, "diff", "--binary", "--no-ext-diff", "HEAD", "--", "."
    ).stdout
    tracked_count = _tracked_count(status)
    untracked_count, untracked_hash = _untracked_digest(root, untracked_paths)
    fingerprint = hashlib.sha256()
    fingerprint.update(b"b0-worktree-v1\0tracked\0")
    fingerprint.update(tracked_diff)
    fingerprint.update(b"\0untracked\0")
    fingerprint.update(bytes.fromhex(untracked_hash))
    return {
        "head": head,
        "branch": branch,
        "dirty": tracked_count > 0 or untracked_count > 0,
        "trackedChangeCount": tracked_count,
        "untrackedFileCount": untracked_count,
        "untrackedAggregateSha256": untracked_hash,
        "worktreeFingerprintSha256": fingerprint.hexdigest(),
    }


def inspect_dataset(dataset_dir: Path, case_file: Path) -> tuple[dict, list[dict]]:
    blockers: list[dict] = []
    manifest_path = dataset_dir / "manifest.json"
    corpus_path = dataset_dir / "corpus.jsonl"
    for path, code in (
        (manifest_path, "DATASET_MANIFEST_MISSING"),
        (corpus_path, "DATASET_CORPUS_MISSING"),
        (case_file, "DATASET_CASE_FILE_MISSING"),
    ):
        if not path.is_file():
            blockers.append({"code": code})
    manifest = None
    manifest_hash = None
    if manifest_path.is_file():
        manifest_hash = _sha256(manifest_path)
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            expected_corpus = manifest["corpusSha256"]
            expected_dev = manifest["splitCounts"]["dev"]
            dataset_id = manifest["datasetId"]
            schema_version = manifest["schemaVersion"]
            if not isinstance(expected_dev, int) or expected_dev < 0:
                raise ValueError("splitCounts.dev must be a non-negative integer")
        except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise CheckerError("Cannot parse dataset manifest") from exc
    else:
        expected_corpus = expected_dev = dataset_id = schema_version = None
    corpus_hash = _sha256(corpus_path) if corpus_path.is_file() else None
    if corpus_hash is not None and corpus_hash.lower() != str(expected_corpus).lower():
        blockers.append({"code": "DATASET_CORPUS_SHA256_MISMATCH"})
    case_hash = _sha256(case_file) if case_file.is_file() else None
    case_count = None
    if case_file.is_file():
        with case_file.open("rb") as stream:
            case_count = sum(1 for line in stream if line.strip())
        if case_count == 0:
            blockers.append({"code": "DATASET_CASE_EMPTY"})
        if expected_dev is not None and case_count != expected_dev:
            blockers.append({"code": "DATASET_DEV_CASE_COUNT_MISMATCH"})
    return ({
        "passed": not blockers,
        "datasetId": dataset_id,
        "schemaVersion": schema_version,
        "manifestSha256": manifest_hash,
        "corpusSha256": corpus_hash,
        "selectedCaseFile": case_file.name,
        "selectedCaseSha256": case_hash,
        "selectedCaseCount": case_count,
        "expectedDevCaseCount": expected_dev,
    }, blockers)


def inspect_metrics_core(root: Path) -> tuple[dict, list[dict]]:
    results = []
    blockers = []
    for name in RUNNERS:
        path = root / RUNNER_DIR / name
        if not path.is_file():
            checks = {"fileExists": False}
        else:
            try:
                source = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                raise CheckerError("Cannot parse Metrics Core runner") from exc
            checks = {
                "fileExists": True,
                "usesCalculator": "RetrievalMetricsCalculator" in source,
                "usesEvaluateCase": ".evaluateCase(" in source,
                "usesSummarizeGroundTruth": ".summarizeGroundTruth(" in source,
                "noPrivateCanonicalCopy": DUPLICATE_FORMULA.search(source) is None,
                "noInlineMathLog": "Math.log(" not in source,
            }
        passed = all(checks.values())
        results.append({"runner": name, "passed": passed, "checks": checks})
        if not passed:
            blockers.append({"code": "METRICS_CORE_STATIC_CHECK_FAILED", "runner": name})
    return {"passed": not blockers, "runners": results}, blockers


def _probe(
    runner: Runner, command: Sequence[str], root: Path, env: Mapping[str, str],
    prefix: str, version_pattern: str | None = None,
) -> tuple[dict, list[dict]]:
    result = runner(command, root, env, TIMEOUT_SECONDS)
    blockers = []
    version = None
    if result.missing:
        blockers.append({"code": prefix + "_COMMAND_MISSING"})
    elif result.timed_out:
        blockers.append({"code": prefix + "_COMMAND_TIMEOUT"})
    elif result.returncode != 0:
        blockers.append({"code": prefix + "_COMMAND_FAILED"})
    elif version_pattern:
        text = (result.stdout + b"\n" + result.stderr).decode("utf-8", "replace")
        match = re.search(version_pattern, text, re.IGNORECASE)
        if match:
            version = match.group(1)
        else:
            blockers.append({"code": prefix + "_VERSION_UNPARSEABLE"})
    return ({
        "passed": not blockers, "exitCode": result.returncode,
        "missing": result.missing, "timedOut": result.timed_out, "version": version,
    }, blockers)


def inspect_prerequisites(
    root: Path, java_home: Path | None, runner: Runner = run_command,
    environ: Mapping[str, str] | None = None,
) -> tuple[dict, list[dict]]:
    env = dict(os.environ if environ is None else environ)
    presence = {name: bool(env.get(name)) for name in ENV_NAMES}
    probe_env = dict(env)
    for name in ENV_NAMES:
        probe_env.pop(name, None)
    if java_home is not None:
        probe_env["JAVA_HOME"] = str(java_home)
        java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    else:
        java = Path("java")
    java_check, blockers = _probe(
        runner, (str(java), "-version"), root, probe_env, "JDK", r'version\s+"([0-9][^"]*)"'
    )
    if java_check["version"]:
        major_text = java_check["version"].split(".")[0]
        major = int(major_text) if major_text != "1" else int(java_check["version"].split(".")[1])
        java_check["major"] = major
        if major < 21:
            java_check["passed"] = False
            blockers.append({"code": "JDK_VERSION_TOO_OLD"})
    maven_command = resolve_command_name("mvn")
    maven_check, maven_blockers = _probe(
        runner, (maven_command, "-version"), root, probe_env, "MAVEN", r"Apache Maven\s+([^\s]+)"
    )
    blockers.extend(maven_blockers)
    docker_cli, docker_cli_blockers = _probe(
        runner, ("docker", "--version"), root, probe_env, "DOCKER_CLI", r"Docker version\s+([^,\s]+)"
    )
    blockers.extend(docker_cli_blockers)
    docker_engine, docker_engine_blockers = _probe(
        runner, ("docker", "version", "--format", "{{.Server.Version}}"),
        root, probe_env, "DOCKER_ENGINE", r"^\s*([^\s]+)\s*$",
    )
    blockers.extend(docker_engine_blockers)
    for name, present in presence.items():
        if not present:
            blockers.append({"code": "ENV_" + name + "_MISSING"})
    return ({
        "passed": not blockers,
        "jdk": java_check,
        "maven": maven_check,
        "docker": {"cli": docker_cli, "engine": docker_engine},
        "requiredEnvironment": {name: {"present": value} for name, value in presence.items()},
    }, blockers)


def build_report(
    config: Config, runner: Runner = run_command,
    environ: Mapping[str, str] | None = None,
    now: dt.datetime | None = None,
) -> dict:
    git = collect_git_snapshot(config.repo_root, runner)
    dataset, dataset_blockers = inspect_dataset(config.dataset_dir, config.case_file)
    metrics, metrics_blockers = inspect_metrics_core(config.repo_root)
    environment, environment_blockers = inspect_prerequisites(
        config.repo_root, config.java_home, runner, environ
    )
    blockers = []
    if git["dirty"]:
        blockers.append({"code": "WORKTREE_DIRTY"})
    blockers.extend(dataset_blockers + metrics_blockers + environment_blockers)
    generated = now or dt.datetime.now().astimezone()
    status = "READY" if not blockers else "NOT_READY"
    return {
        "reportSchema": {"name": "runtimeChecksOnly/readiness", "version": 1},
        "runtimeChecksOnly": True,
        "formalBaselineRun": False,
        "generatedAt": generated.isoformat(),
        "evaluationConfig": {"retrievalK": 10, "parameterSelectionPerformed": False},
        "readiness": {"status": status, "blockers": blockers},
        "git": git,
        "dataset": dataset,
        "metricsCore": metrics,
        "environment": environment,
    }


def write_report_exclusive(path: Path, report: dict) -> None:
    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    try:
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
    except (FileExistsError, OSError) as exc:
        raise CheckerError("Cannot exclusively create output report") from exc


def run_readiness(
    config: Config, runner: Runner = run_command,
    environ: Mapping[str, str] | None = None,
    now: dt.datetime | None = None,
) -> int:
    try:
        if config.output.exists() or not config.output.parent.is_dir():
            raise CheckerError("Output must be a new file in an existing directory")
        report = build_report(config, runner, environ, now)
        write_report_exclusive(config.output, report)
        print(f"{report['readiness']['status']}: {config.output}")
        return 0 if report["readiness"]["status"] == "READY" else 2
    except CheckerError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


def _resolve(root: Path, value: str) -> Path:
    path = Path(value)
    return path.resolve() if path.is_absolute() else (root / path).resolve()


def parse_config(argv: Sequence[str] | None = None) -> Config:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--dataset-dir", default=DEFAULT_DATASET)
    parser.add_argument("--case-file", default=DEFAULT_CASE)
    parser.add_argument("--output")
    parser.add_argument("--java-home")
    args = parser.parse_args(argv)
    root = Path(args.repo_root).resolve()
    dataset = _resolve(root, args.dataset_dir)
    case_arg = Path(args.case_file)
    case_file = case_arg.resolve() if case_arg.is_absolute() else (dataset / case_arg).resolve()
    if args.output:
        output = _resolve(root, args.output)
    else:
        now = dt.datetime.now().astimezone()
        stamp = now.strftime("%Y%m%dT%H%M%S%f%z")
        output = root / "backend/evals/reports" / f"{now.date()}-b0-readiness-{stamp}.json"
    java_home = Path(args.java_home).resolve() if args.java_home else None
    return Config(root, dataset, case_file, output, java_home)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        return run_readiness(parse_config(argv))
    except (OSError, ValueError) as exc:
        print(f"ERROR: invalid parameters: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
