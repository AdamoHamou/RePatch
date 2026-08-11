#!/usr/bin/env python3
"""RePatch 2.0 validation study — analysis, tables, and figures.

Reads the machine-readable result dataset (dataset.jsonl, one JSON record
per benchmark case, produced by the container's `repatch validate`) and
generates the study's reporting deliverables:

  report/summary.md          stage funnel, outcome distribution, causes,
                             runtime summary, per-project breakdown
  report/table1_funnel.csv   stage-wise validation (cases + percentage)
  report/table2_outcomes.csv final outcome distribution
  report/per_project.csv     per-project outcome counts
  report/fig1_funnel.png     validation progression      (needs matplotlib)
  report/fig2_outcomes.png   final outcome distribution  (needs matplotlib)

Everything is computed from the dataset — no hand-entered counts.

Usage: python3 analyze.py <dir-containing-dataset.jsonl> [--min-group 10]
"""

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

OUTCOMES = ["VALID", "INTEGRATION_FAIL", "BUILD_FAIL", "TEST_FAIL",
            "INCONCLUSIVE", "PENDING"]


def wilson_ci(k, n, z=1.96):
    """95% Wilson score interval for a proportion, as (lo, hi)."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    half = (z / denom) * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (max(0.0, center - half), min(1.0, center + half))


def pct(k, n):
    return "%.1f%%" % (100.0 * k / n) if n else "-"


def ci_str(k, n):
    lo, hi = wilson_ci(k, n)
    return "[%.1f%%, %.1f%%]" % (100 * lo, 100 * hi) if n else "-"


def load(path):
    records = []
    for line in Path(path).read_text().splitlines():
        if line.strip():
            records.append(json.loads(line))
    return records


def outcome(rec):
    return (rec.get("validation") or {}).get("final_outcome") or "PENDING"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset_dir")
    ap.add_argument("--min-group", type=int, default=10,
                    help="minimum cases for a per-project row")
    args = ap.parse_args()

    ds = Path(args.dataset_dir)
    dataset = ds / "dataset.jsonl" if ds.is_dir() else ds
    if not dataset.exists():
        sys.exit("no dataset.jsonl at %s" % ds)
    records = load(dataset)
    out = dataset.parent / "report"
    out.mkdir(exist_ok=True)

    # ---- stage funnel -----------------------------------------------------
    evaluated = [r for r in records if outcome(r) != "PENDING"]
    conflict_free = [r for r in evaluated
                     if r["integration"]["status"] in ("CONFLICT_FREE", "GIT_CLEAN")]
    builds = [r for r in conflict_free
              if (r.get("post") or {}).get("build") == "PASS"]
    tests_pass = [r for r in builds if outcome(r) == "VALID"]

    n_eval = len(evaluated)
    funnel = [
        ("Evaluated", n_eval, n_eval, ""),
        ("Conflict-free integration", len(conflict_free), n_eval,
         ci_str(len(conflict_free), n_eval)),
        ("Build succeeds", len(builds), len(conflict_free),
         ci_str(len(builds), len(conflict_free))),
        ("Existing tests pass", len(tests_pass), len(builds),
         ci_str(len(tests_pass), len(builds))),
    ]
    with (out / "table1_funnel.csv").open("w") as f:
        f.write("stage,cases,denominator,percentage,wilson95\n")
        for stage, k, n, ci in funnel:
            f.write("%s,%d,%d,%s,%s\n" % (stage, k, n, pct(k, n), ci))

    # ---- outcome distribution --------------------------------------------
    by_outcome = Counter(outcome(r) for r in records)
    with (out / "table2_outcomes.csv").open("w") as f:
        f.write("outcome,cases,percentage_of_evaluated\n")
        for o in OUTCOMES:
            denom = n_eval if o != "PENDING" else len(records)
            f.write("%s,%d,%s\n" % (o, by_outcome.get(o, 0),
                                    pct(by_outcome.get(o, 0), denom)))

    causes = Counter((r.get("validation") or {}).get("inconclusive_cause")
                     for r in records if outcome(r) == "INCONCLUSIVE")

    # ---- per-project ------------------------------------------------------
    per_project = defaultdict(Counter)
    for r in records:
        per_project[r["target_repo"].split("github.com/")[-1]][outcome(r)] += 1
    with (out / "per_project.csv").open("w") as f:
        f.write("target_repo,total," + ",".join(OUTCOMES) + "\n")
        for repo in sorted(per_project):
            c = per_project[repo]
            f.write("%s,%d,%s\n" % (repo, sum(c.values()),
                                    ",".join(str(c.get(o, 0)) for o in OUTCOMES)))

    # ---- runtimes ----------------------------------------------------------
    runtimes = sorted(r["integration"]["runtime_ms"] for r in records
                      if (r["integration"].get("runtime_ms") or 0) > 0)

    def quantile(q):
        return runtimes[min(len(runtimes) - 1, int(q * len(runtimes)))] / 1000.0

    # ---- summary.md --------------------------------------------------------
    md = []
    md.append("# RePatch 2.0 validation study — summary\n")
    md.append("Dataset: `%s` (%d cases, %d evaluated, %d pending)\n"
              % (dataset, len(records), n_eval,
                 by_outcome.get("PENDING", 0)))
    md.append("## Table 1: stage-wise RePatch validation\n")
    md.append("| Stage | Cases | Percentage | 95% CI |")
    md.append("|---|---|---|---|")
    for stage, k, n, ci in funnel:
        md.append("| %s | %d | %s | %s |" % (stage, k, pct(k, n), ci))
    md.append("\n(percentages are stage-conditional: each row over the row above)\n")
    md.append("## Table 2: final outcome distribution\n")
    md.append("| Outcome | Cases | % of evaluated |")
    md.append("|---|---|---|")
    for o in OUTCOMES:
        denom = n_eval if o != "PENDING" else len(records)
        md.append("| %s | %d | %s |" % (o, by_outcome.get(o, 0),
                                        pct(by_outcome.get(o, 0), denom)))
    if causes:
        md.append("\n## Inconclusive causes\n")
        md.append("| Cause | Cases |")
        md.append("|---|---|")
        for cause, n in causes.most_common():
            md.append("| %s | %d |" % (cause, n))
    if runtimes:
        md.append("\n## RePatch runtime (integration stage, where recorded)\n")
        md.append("n=%d; median %.1fs; p90 %.1fs; max %.1fs"
                  % (len(runtimes), quantile(0.5), quantile(0.9),
                     runtimes[-1] / 1000.0))
    md.append("\n## Per-project outcomes (groups with >= %d cases)\n" % args.min_group)
    md.append("| Target repo | Total | " + " | ".join(OUTCOMES) + " |")
    md.append("|---" * (len(OUTCOMES) + 2) + "|")
    for repo in sorted(per_project):
        c = per_project[repo]
        if sum(c.values()) >= args.min_group:
            md.append("| %s | %d | %s |" % (repo, sum(c.values()),
                      " | ".join(str(c.get(o, 0)) for o in OUTCOMES)))
    md.append("\n## Interpretation questions (study section 10)\n")
    md.append("- Conflict-free target-side change: %d/%d (%s)"
              % (len(conflict_free), n_eval, pct(len(conflict_free), n_eval)))
    md.append("- Conflict-free integrations that build: %d/%d (%s)"
              % (len(builds), len(conflict_free), pct(len(builds), len(conflict_free))))
    md.append("- Buildable results whose existing tests remain valid: %d/%d (%s)"
              % (len(tests_pass), len(builds), pct(len(tests_pass), len(builds))))
    md.append("- Gap between conflict-free success and build/test-validated success: "
              "%s -> %s of evaluated cases"
              % (pct(len(conflict_free), n_eval), pct(len(tests_pass), n_eval)))
    (out / "summary.md").write_text("\n".join(md) + "\n")

    # ---- figures ------------------------------------------------------------
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        stages = [f[0] for f in funnel]
        values = [f[1] for f in funnel]
        fig, ax = plt.subplots(figsize=(7, 4))
        ax.barh(range(len(stages))[::-1], values, color="#4878a8")
        ax.set_yticks(range(len(stages))[::-1], stages)
        ax.set_xlabel("cases")
        ax.set_title("Validation progression")
        for i, v in enumerate(values):
            ax.text(v, len(stages) - 1 - i, " %d" % v, va="center")
        fig.tight_layout()
        fig.savefig(out / "fig1_funnel.png", dpi=150)

        fig, ax = plt.subplots(figsize=(7, 4))
        labels = [o for o in OUTCOMES if by_outcome.get(o)]
        vals = [by_outcome[o] for o in labels]
        ax.bar(labels, vals, color="#4878a8")
        ax.set_ylabel("cases")
        ax.set_title("Final outcome distribution")
        for i, v in enumerate(vals):
            ax.text(i, v, str(v), ha="center", va="bottom")
        plt.xticks(rotation=20, ha="right")
        fig.tight_layout()
        fig.savefig(out / "fig2_outcomes.png", dpi=150)
        print("[analyze] figures written")
    except ImportError:
        print("[analyze] matplotlib not available — tables/summary only")

    print("[analyze] report written to %s" % out)


if __name__ == "__main__":
    main()
