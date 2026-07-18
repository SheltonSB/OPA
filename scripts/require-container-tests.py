#!/usr/bin/env python3
"""Fail a CI job when Docker-backed integration tests did not execute.

The normal developer build is allowed to skip Testcontainers when Docker is not
available. A hosted CI job that claims integration evidence must use this
guard after Maven so a misconfigured runner cannot report a false green build.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    report_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports")
    reports = sorted(report_dir.glob("TEST-dev.opaguard.integration.*.xml"))
    if not reports:
        print(f"No container integration reports found in {report_dir}", file=sys.stderr)
        return 2

    failures: list[str] = []
    total = skipped = errors = failed = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        tests = int(suite.attrib.get("tests", "0"))
        suite_skipped = int(suite.attrib.get("skipped", "0"))
        suite_errors = int(suite.attrib.get("errors", "0"))
        suite_failed = int(suite.attrib.get("failures", "0"))
        total += tests
        skipped += suite_skipped
        errors += suite_errors
        failed += suite_failed
        if suite_skipped or suite_errors or suite_failed:
            failures.append(
                f"{report.name}: tests={tests}, skipped={suite_skipped}, "
                f"errors={suite_errors}, failures={suite_failed}"
            )

    print(f"Container integration evidence: {total} tests, {skipped} skipped, "
          f"{errors} errors, {failed} failures")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        print(
            "Docker-backed integration tests must execute on the CI runner; "
            "check Docker availability and Testcontainers configuration.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
