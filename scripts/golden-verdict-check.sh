#!/usr/bin/env bash
#
# SPEC-12c golden regression check: compare the verdicts a sample-data run
# wrote to its database against the committed golden baseline.
#
#   Usage: scripts/golden-verdict-check.sh <database> [mysql args...]
#   e.g.:  scripts/golden-verdict-check.sh repatch_golden_baseline -h127.0.0.1 -urepatch -prepatch
#
# The golden file records, for each sample patch, the Git-CherryPick and
# RePatch verdicts as number,merge_tool,files/conflicts/loc. Patches the
# baseline cherry-pick auto-merges cleanly produce no merge_result rows by
# design and are recorded with verdict "-" and is_conflicting 0.
set -uo pipefail

DB="${1:?usage: golden-verdict-check.sh <database> [mysql args...]}"
shift
GOLDEN="$(dirname "$0")/golden_sample_verdicts.csv"
[ -f "$GOLDEN" ] || { echo "FAIL: golden file missing at $GOLDEN"; exit 2; }

ACTUAL=$(mktemp)
trap 'rm -f "$ACTUAL"' EXIT
{
  mysql "$@" -N -e "
    SELECT p.number, mr.merge_tool,
           CONCAT(mr.total_conflicting_files,'/',mr.total_conflicts,'/',mr.total_conflicting_loc)
    FROM patch p
    JOIN merge_commit mc ON mc.patch_id = p.id
    JOIN merge_result mr ON mr.merge_commit_id = mc.id
    ORDER BY p.number, mr.merge_tool;" "$DB"
  mysql "$@" -N -e "
    SELECT p.number, 'none', '-'
    FROM patch p
    WHERE p.is_conflicting = 0 AND p.is_done = 1
    ORDER BY p.number;" "$DB"
} | tr '\t' ',' | sort > "$ACTUAL"

if diff -u <(grep -v '^#' "$GOLDEN" | sort) "$ACTUAL"; then
  echo "GOLDEN PASS: verdicts match $GOLDEN"
else
  echo "GOLDEN FAIL: verdicts differ from $GOLDEN (see diff above)"
  exit 1
fi
