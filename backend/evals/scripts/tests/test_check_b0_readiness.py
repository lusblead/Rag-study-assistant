from __future__ import annotations

import datetime as dt
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "check_b0_readiness.py"
SPEC = importlib.util.spec_from_file_location("check_b0_readiness", SCRIPT)
checker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = checker
SPEC.loader.exec_module(checker)


class FakeRunner:
    def __init__(self, root: Path, dirty=False, untracked=(), overrides=None):
        self.root = root
        self.dirty = dirty
        self.untracked = tuple(untracked)
        self.overrides = overrides or {}
        self.calls = []
        self.environments = []

    def __call__(self, command, cwd, env, timeout):
        self.calls.append(tuple(command))
        self.environments.append(dict(env or {}))
        key = self._key(command)
        if key in self.overrides:
            return self.overrides[key]
        if command[0] == "git":
            args = tuple(command[1:])
            if args == ("rev-parse", "--show-toplevel"):
                return checker.CommandResult(0, str(self.root).encode())
            if args == ("rev-parse", "HEAD"):
                return checker.CommandResult(0, b"a" * 40)
            if args[:3] == ("symbolic-ref", "--quiet", "--short"):
                return checker.CommandResult(0, b"main\n")
            if args[0] == "status":
                status = b" M tracked.txt\0" if self.dirty else b""
                status += b"".join(b"?? " + p.encode() + b"\0" for p in self.untracked)
                return checker.CommandResult(0, status)
            if args[0] == "ls-files":
                return checker.CommandResult(0, b"\0".join(p.encode() for p in self.untracked) + (b"\0" if self.untracked else b""))
            if args[0] == "diff":
                return checker.CommandResult(0, b"tracked-diff" if self.dirty else b"")
        if key == "java":
            return checker.CommandResult(0, stderr=b'openjdk version "25.0.1"')
        if key == "mvn":
            return checker.CommandResult(0, b"Apache Maven 3.9.11")
        if key == "docker-cli":
            return checker.CommandResult(0, b"Docker version 28.3.2, build x")
        if key == "docker-engine":
            return checker.CommandResult(0, b"28.3.2\n")
        raise AssertionError(command)

    @staticmethod
    def _key(command):
        name = Path(command[0]).name.lower()
        if name.startswith("java"):
            return "java"
        if name.startswith("mvn"):
            return "mvn"
        if name == "docker" and len(command) > 1 and command[1] == "--version":
            return "docker-cli"
        if name == "docker":
            return "docker-engine"
        return name


class ReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        (self.root / "backend/evals/reports").mkdir(parents=True)
        self.dataset = self.root / checker.DEFAULT_DATASET
        self.dataset.mkdir(parents=True)
        corpus = b'{"id":1}\n'
        (self.dataset / "corpus.jsonl").write_bytes(corpus)
        self.case = self.dataset / checker.DEFAULT_CASE
        self.case.write_bytes(b'{"case":1}\n{"case":2}\n')
        manifest = {
            "datasetId": "fake-reviewed",
            "schemaVersion": 1,
            "corpusSha256": hashlib.sha256(corpus).hexdigest(),
            "splitCounts": {"dev": 2},
        }
        (self.dataset / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        runner_dir = self.root / checker.RUNNER_DIR
        runner_dir.mkdir(parents=True)
        source = "RetrievalMetricsCalculator x; x.evaluateCase(); x.summarizeGroundTruth();"
        for name in checker.RUNNERS:
            (runner_dir / name).write_text(source, encoding="utf-8")
        self.env = {name: "present" for name in checker.ENV_NAMES}

    def tearDown(self):
        self.temp.cleanup()

    def config(self, name="report.json"):
        return checker.Config(
            self.root, self.dataset, self.case,
            self.root / "backend/evals/reports" / name, None,
        )

    def read(self, config):
        return json.loads(config.output.read_text(encoding="utf-8"))

    def test_clean_complete_snapshot_is_ready(self):
        config = self.config()
        code = checker.run_readiness(config, FakeRunner(self.root), self.env)
        self.assertEqual(0, code)
        self.assertEqual("READY", self.read(config)["readiness"]["status"])

    def test_dirty_worktree_is_not_ready(self):
        config = self.config()
        code = checker.run_readiness(config, FakeRunner(self.root, dirty=True), self.env)
        self.assertEqual(2, code)
        codes = {item["code"] for item in self.read(config)["readiness"]["blockers"]}
        self.assertIn("WORKTREE_DIRTY", codes)

    def test_existing_output_is_never_overwritten(self):
        config = self.config()
        original = b"original-bytes"
        config.output.write_bytes(original)
        with redirect_stderr(StringIO()):
            code = checker.run_readiness(config, FakeRunner(self.root), self.env)
        self.assertEqual(1, code)
        self.assertEqual(original, config.output.read_bytes())

    def test_secret_values_are_not_written_and_baseline_is_false(self):
        config = self.config()
        env = dict(self.env)
        env[checker.ENV_NAMES[-1]] = "SECRET_SENTINEL_9281"
        self.assertEqual(0, checker.run_readiness(config, FakeRunner(self.root), env))
        text = config.output.read_text(encoding="utf-8")
        self.assertNotIn("SECRET_SENTINEL_9281", text)
        report = json.loads(text)
        self.assertFalse(report["formalBaselineRun"])
        self.assertEqual("runtimeChecksOnly/readiness", report["reportSchema"]["name"])

    def test_probe_subprocess_environment_excludes_embedding_secrets(self):
        config = self.config()
        env = {name: "SECRET_SENTINEL" for name in checker.ENV_NAMES}
        runner = FakeRunner(self.root)
        self.assertEqual(0, checker.run_readiness(config, runner, env))
        probe_environments = runner.environments[6:]
        self.assertEqual(4, len(probe_environments))
        for child_env in probe_environments:
            for name in checker.ENV_NAMES:
                self.assertNotIn(name, child_env)

    def test_windows_command_resolution_prefers_mvn_cmd_without_shell(self):
        candidates = []

        def fake_which(name):
            candidates.append(name)
            if name == "mvn.cmd":
                return r"C:\Program Files\apache-maven\bin\mvn.cmd"
            return None

        resolved = checker.resolve_command_name(
            "mvn", platform="nt", which=fake_which
        )
        self.assertEqual(
            r"C:\Program Files\apache-maven\bin\mvn.cmd", resolved
        )
        self.assertEqual(["mvn.cmd"], candidates)

    def test_fingerprint_changes_when_untracked_content_changes(self):
        path = self.root / "untracked.bin"
        path.write_bytes(b"one")
        runner = FakeRunner(self.root, untracked=("untracked.bin",))
        first = checker.collect_git_snapshot(self.root, runner)
        path.write_bytes(b"two")
        second = checker.collect_git_snapshot(self.root, runner)
        self.assertNotEqual(first["worktreeFingerprintSha256"], second["worktreeFingerprintSha256"])
        self.assertNotIn("untracked.bin", json.dumps(second))

    def test_command_timeout_and_missing_are_blockers_without_retry(self):
        config = self.config()
        overrides = {
            "java": checker.CommandResult(None, timed_out=True),
            "mvn": checker.CommandResult(None, missing=True),
        }
        runner = FakeRunner(self.root, overrides=overrides)
        self.assertEqual(2, checker.run_readiness(config, runner, self.env))
        codes = {item["code"] for item in self.read(config)["readiness"]["blockers"]}
        self.assertIn("JDK_COMMAND_TIMEOUT", codes)
        self.assertIn("MAVEN_COMMAND_MISSING", codes)
        self.assertEqual(1, sum(1 for call in runner.calls if FakeRunner._key(call) == "java"))
        self.assertEqual(1, sum(1 for call in runner.calls if FakeRunner._key(call) == "mvn"))


if __name__ == "__main__":
    unittest.main()
