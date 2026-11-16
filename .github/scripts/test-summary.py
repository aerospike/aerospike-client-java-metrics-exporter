"""Write a Markdown summary of the Surefire and Failsafe XML reports.

Usage: test-summary.py [TITLE]

Appends to $GITHUB_STEP_SUMMARY when it is set, otherwise prints to stdout.
"""

import glob
import html
import os
import sys
import xml.etree.ElementTree as ET

KINDS = {"surefire-reports": "Unit", "failsafe-reports": "Integration"}
COUNTS = ("tests", "failures", "errors", "skipped")


def first_line(text):
    lines = (text or "").strip().splitlines()
    return lines[0][:200] if lines else ""


def main():
    title = "Test results" + (f" ({sys.argv[1]})" if len(sys.argv) > 1 else "")
    rows = []
    failed = []
    totals = dict.fromkeys(COUNTS, 0)

    for path in sorted(glob.glob("**/target/*-reports/TEST-*.xml", recursive=True)):
        suite = ET.parse(path).getroot()
        counts = {name: int(suite.get(name, 0)) for name in COUNTS}
        for name in COUNTS:
            totals[name] += counts[name]
        kind = KINDS.get(os.path.basename(os.path.dirname(path)), "Other")
        rows.append((kind, suite.get("name", "").rsplit(".", 1)[-1], counts))

        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                test = case.get("classname", "").rsplit(".", 1)[-1] + "." + case.get("name", "")
                reason = first_line(problem.get("message")) or problem.get("type", "")
                failed.append(f"- `{test}`: {html.escape(reason, quote=False)}")

    lines = [f"### {title}", ""]
    if not rows:
        lines.append("No test reports were found, so the build probably failed before the tests ran.")
    else:
        lines.append("| Kind | Class | Tests | Failures | Errors | Skipped |")
        lines.append("| --- | --- | --: | --: | --: | --: |")
        for kind, name, counts in rows:
            values = " | ".join(str(counts[n]) for n in COUNTS)
            lines.append(f"| {kind} | `{name}` | {values} |")
        lines += ["", "**Total:** " + ", ".join(f"{totals[n]} {n}" for n in COUNTS)]
        if failed:
            lines += ["", "#### Failed tests", ""] + failed

    output = "\n".join(lines) + "\n"
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(output)
    else:
        sys.stdout.write(output)


if __name__ == "__main__":
    main()
