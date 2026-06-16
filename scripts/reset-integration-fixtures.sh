#!/usr/bin/env bash
# Reset the test fixtures the integration pipeline depends on, so a fresh
# runIde -Pmode=integration starts from a known-good state.
#
# Wired into Gradle: the runIde task depends on resetIntegrationFixtures
# whenever -Pmode=integration is set, so invoking the integration mode
# from the IDE or CLI auto-resets first. Direct invocation
# (`bash scripts/reset-integration-fixtures.sh`) also works for ad-hoc use.
#
# Evaluation checkouts are described by
# src/main/resources/sample_data/repatch_integration_projects
# (mainlineUrl,variantUrl,branch,pinnedSha — one project per line). Each
# checkout lives at ~/repatch-integration-projects/<Owner>-<RepoName>,
# derived from the variant URL (same derivation as RepoNaming.java; keep
# the two in sync). A missing checkout is NOT an error: the pipeline
# clones it on first run.
set -euo pipefail

DB_NAME="refactoring_aware_integration_repatch"
DB_USER="${RPATCH_DB_USER:-repatch}"
DB_PASS="${RPATCH_DB_PASS:-repatch}"
DB_HOST="${RPATCH_DB_HOST:-127.0.0.1}"
DATA_DIR="${HOME}/repatch-integration-projects"

# PROJECT_ROOT is exported by the Gradle task; fall back to script-relative
# resolution so direct invocation also finds the sandbox.
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
# DATASET (exported by the Gradle task from -PdataSet) chooses which bundled
# project list to reset/clone: "complete" -> complete_data, else sample_data.
if [ "${DATASET:-sample}" = "complete" ]; then
    DATA_SUBDIR="complete_data"
else
    DATA_SUBDIR="sample_data"
fi
echo "[reset-fixtures] dataset = ${DATA_SUBDIR}"
PROJECTS_FILE="${PROJECT_ROOT}/src/main/resources/${DATA_SUBDIR}/repatch_integration_projects"
SANDBOX_LOCK="${PROJECT_ROOT}/.intellijPlatform/sandbox/RePatch/IC-2024.3.7/config/.lock"

echo "[reset-fixtures] dropping database ${DB_NAME}"
MYSQL_PWD="${DB_PASS}" mysql -h "${DB_HOST}" -u "${DB_USER}" \
    -e "DROP DATABASE IF EXISTS ${DB_NAME};"

if [ ! -f "${PROJECTS_FILE}" ]; then
    echo "[reset-fixtures] FATAL: projects file not found: ${PROJECTS_FILE}" >&2
    exit 1
fi

# `|| [ -n "${mainline_url}" ]` keeps the final line when the file has no
# trailing newline (the complete_data list does not end in one).
while IFS=, read -r mainline_url variant_url branch pinned_sha || [ -n "${mainline_url}" ]; do
    # blank lines skipped; only the variant URL is required. branch+pinnedSha
    # are optional (2-column entries, e.g. complete_data): without a pin we
    # re-baseline to the fork's default remote branch instead of a fixed SHA.
    [ -z "${variant_url}" ] && continue
    # trim CR/whitespace that a non-LF-terminated last column can carry
    pinned_sha="${pinned_sha//[$'\r\n\t ']/}"
    # <Owner>-<RepoName> from the variant URL, e.g.
    # https://github.com/linkedin/kafka -> linkedin-kafka
    url="${variant_url%/}"; url="${url%.git}"
    repo="${url##*/}"
    owner_path="${url%/*}"; owner="${owner_path##*/}"
    checkout_dir="${DATA_DIR}/${owner}-${repo}"

    if [ ! -d "${checkout_dir}/.git" ]; then
        echo "[reset-fixtures] ${checkout_dir} not present — pipeline will clone it on first run"
        continue
    fi
    if [ -n "${pinned_sha}" ]; then
        echo "[reset-fixtures] resetting ${owner}-${repo} to ${pinned_sha:0:12}"
        git -C "${checkout_dir}" reset --hard "${pinned_sha}" >/dev/null
    else
        default_ref="$(git -C "${checkout_dir}" symbolic-ref --quiet refs/remotes/origin/HEAD 2>/dev/null || true)"
        if [ -n "${default_ref}" ]; then
            echo "[reset-fixtures] re-baselining ${owner}-${repo} (unpinned) to ${default_ref}"
            git -C "${checkout_dir}" reset --hard "${default_ref}" >/dev/null
        else
            echo "[reset-fixtures] WARN: ${owner}-${repo} unpinned and no origin/HEAD; leaving as-is (delete the dir to force a fresh clone)"
        fi
    fi
    echo "[reset-fixtures] removing ${owner}-${repo}/.idea"
    rm -rf "${checkout_dir}/.idea"
done < "${PROJECTS_FILE}"

echo "[reset-fixtures] removing ~/results"
rm -rf "${HOME}/results"

if [ -f "${SANDBOX_LOCK}" ]; then
    echo "[reset-fixtures] removing leftover sandbox lock"
    rm -f "${SANDBOX_LOCK}"
fi

echo "[reset-fixtures] done"
