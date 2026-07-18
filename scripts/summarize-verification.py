#!/usr/bin/env python3
"""Summarize Surefire and JaCoCo evidence without external services."""

from __future__ import annotations

import argparse
import html
from pathlib import Path
import xml.etree.ElementTree as ET


def test_totals(report_directory: Path) -> tuple[int, int, int, int]:
    totals = [0, 0, 0, 0]
    for report in sorted(report_directory.glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        totals[0] += int(suite.attrib.get("tests", 0))
        totals[1] += int(suite.attrib.get("failures", 0))
        totals[2] += int(suite.attrib.get("errors", 0))
        totals[3] += int(suite.attrib.get("skipped", 0))
    return tuple(totals)


def line_coverage(report: Path) -> float:
    root = ET.parse(report).getroot()
    counter = next(item for item in root.findall("counter") if item.attrib["type"] == "LINE")
    missed = int(counter.attrib["missed"])
    covered = int(counter.attrib["covered"])
    return 0.0 if missed + covered == 0 else covered * 100.0 / (missed + covered)


def badge(label: str, value: str, color: str) -> str:
    left = max(74, len(label) * 7 + 14)
    right = max(54, len(value) * 7 + 14)
    total = left + right
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20" role="img" aria-label="{html.escape(label)}: {html.escape(value)}">
  <title>{html.escape(label)}: {html.escape(value)}</title>
  <linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="r"><rect width="{total}" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#r)"><rect width="{left}" height="20" fill="#555"/><rect x="{left}" width="{right}" height="20" fill="{color}"/><rect width="{total}" height="20" fill="url(#s)"/></g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11"><text x="{left / 2}" y="15">{html.escape(label)}</text><text x="{left + right / 2}" y="15">{html.escape(value)}</text></g>
</svg>
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("surefire_reports", type=Path)
    parser.add_argument("jacoco_xml", type=Path)
    parser.add_argument("--write-badges", type=Path)
    args = parser.parse_args()

    tests, failures, errors, skipped = test_totals(args.surefire_reports)
    coverage = line_coverage(args.jacoco_xml)
    passed = tests - failures - errors - skipped
    print("## Verification evidence\n")
    print("| Tests | Passed | Failed | Errors | Skipped | Line coverage |")
    print("|---:|---:|---:|---:|---:|---:|")
    print(f"| {tests} | {passed} | {failures} | {errors} | {skipped} | {coverage:.1f}% |")

    if args.write_badges:
        args.write_badges.mkdir(parents=True, exist_ok=True)
        # A skipped integration test is not a green evidence signal. Keep the
        # badge useful on developer machines while making a partial run
        # visually distinct from a complete CI run.
        test_color = ("#e05d44" if failures + errors else
                      "#dfb317" if skipped else "#4c1")
        coverage_color = "#4c1" if coverage >= 80 else "#dfb317" if coverage >= 60 else "#e05d44"
        (args.write_badges / "tests.svg").write_text(
            badge("tests", f"{passed}/{tests} passed", test_color), encoding="utf-8")
        (args.write_badges / "coverage.svg").write_text(
            badge("coverage", f"{coverage:.1f}%", coverage_color), encoding="utf-8")


if __name__ == "__main__":
    main()
