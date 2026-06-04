#!/usr/bin/env bash
# Reset the test fixtures the integration pipeline depends on, so a fresh
# runIde -Pmode=integration starts from a known-good state.
#
# Wired into Gradle: the runIde task depends on resetIntegrationFixtures
# whenever -Pmode=integration is set, so invoking the integration mode
# from the IDE or CLI auto-resets first. Direct invocation
# (`bash scripts/reset-integration-fixtures.sh`) also works for ad-hoc use.
#
# Currently hardcoded to the linkedin/kafka 5-PR fixture. If additional
# evaluationProject targets are added, parameterize KAFKA_DIR and
# KAFKA_PINNED_SHA via env vars or a config file.
set -euo pipefail

KAFKA_DIR="${HOME}/repatch-integration-projects/kafka"
KAFKA_PINNED_SHA="31df5cec3298e31b55888307929904df83cc8753"
DB_NAME="refactoring_aware_integration_repatch"
DB_USER="${RPATCH_DB_USER:-repatch}"
DB_PASS="${RPATCH_DB_PASS:-repatch}"
DB_HOST="${RPATCH_DB_HOST:-127.0.0.1}"

# PROJECT_ROOT is exported by the Gradle task; fall back to script-relative
# resolution so direct invocation also finds the sandbox.
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SANDBOX_LOCK="${PROJECT_ROOT}/.intellijPlatform/sandbox/RePatch/IC-2024.3.7/config/.lock"

echo "[reset-fixtures] dropping database ${DB_NAME}"
MYSQL_PWD="${DB_PASS}" mysql -h "${DB_HOST}" -u "${DB_USER}" \
    -e "DROP DATABASE IF EXISTS ${DB_NAME};"

echo "[reset-fixtures] resetting kafka to ${KAFKA_PINNED_SHA:0:12}"
if [ ! -d "${KAFKA_DIR}/.git" ]; then
    echo "[reset-fixtures] FATAL: ${KAFKA_DIR} is not a git checkout" >&2
    exit 1
fi
git -C "${KAFKA_DIR}" reset --hard "${KAFKA_PINNED_SHA}" >/dev/null

echo "[reset-fixtures] removing kafka/.idea and ~/results"
rm -rf "${KAFKA_DIR}/.idea" "${HOME}/results"

if [ -f "${SANDBOX_LOCK}" ]; then
    echo "[reset-fixtures] removing leftover sandbox lock"
    rm -f "${SANDBOX_LOCK}"
fi

echo "[reset-fixtures] done"
