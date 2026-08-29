"""CLI entry point; validate-only is the default and sends no HTTP."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from performance_workflow import (
    WorkflowError,
    execute_workflow,
    load_json_object,
    runtime_fixture_fingerprint,
    validate_only,
    validate_profile,
)


HERE = Path(__file__).resolve().parent


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate or explicitly execute the local RAG performance workflow")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--validate-only", action="store_true", help="validate local artifacts without HTTP (default)")
    action.add_argument("--execute", action="store_true", help="request gated loopback execution")
    action.add_argument(
        "--fingerprint-workload",
        action="store_true",
        help="hash the environment-only workload without HTTP or persistence",
    )
    parser.add_argument("--profile", type=Path, default=HERE / "performance-profile.example.json")
    parser.add_argument("--jmx", type=Path, default=HERE / "rag-layered-performance.jmx")
    parser.add_argument("--schema", type=Path, default=HERE / "performance-report-v1.schema.json")
    parser.add_argument("--jmeter", help="path to the Apache JMeter 5.6.3 executable")
    parser.add_argument("--output-root", type=Path, default=HERE / "runs")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.fingerprint_workload:
            profile = load_json_object(args.profile.resolve())
            validate_profile(profile)
            fingerprint = runtime_fixture_fingerprint(profile, os.environ)
            print(
                json.dumps(
                    {
                        "mode": "fingerprint-workload",
                        "httpRequestsSent": 0,
                        "fixtureFingerprintSha256": fingerprint,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
            return 0
        if not args.execute:
            summary = validate_only(args.profile.resolve(), args.jmx.resolve(), args.schema.resolve())
            print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
            return 0
        repo_root = HERE.parents[2]
        report_path = execute_workflow(
            repo_root=repo_root,
            profile_path=args.profile.resolve(),
            jmx_path=args.jmx.resolve(),
            schema_path=args.schema.resolve(),
            output_root=args.output_root.resolve(),
            jmeter_path=args.jmeter,
            explicit_execute=True,
        )
        print(json.dumps({"report": str(report_path), "mode": "execute"}, ensure_ascii=False, sort_keys=True))
        return 0
    except WorkflowError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except FileExistsError:
        print("ERROR: exclusive run output already exists", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
