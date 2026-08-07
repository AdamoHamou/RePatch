#!/usr/bin/env bash
#
# RePatch 2.0 headless entrypoint.
#
# Environment contract (all optional):
#   DB_HOST          MySQL host                     (default: mysql)
#   JDBC_USER        MySQL user                     (default: repatch)
#   JDBC_PASSWORD    MySQL password                 (default: repatch)
#   RP_DB            database name for this run     (default: repatch_run)
#   RP_KEEP_DB=1     reuse RP_DB instead of dropping and recreating it
#   RP_DATASET       sample | complete              (default: sample)
#   RP_PRS           comma-separated PR numbers — overrides the dataset with
#                    single-project kafka scenarios (e.g. RP_PRS=16954,12363)
#   RP_FORK_URL      evaluation fork clone URL
#                    (default: https://github.com/danielogen/linkedin — the
#                    paper's frozen fork mirror)
#   RP_MAINLINE_URL  mainline remote URL (default: https://github.com/apache/kafka)
#   RP_TIMEOUT       seconds before the run is killed (default: 5400)
#   RP_GOLDEN_CHECK=1  after a default-sample run, diff the verdicts against
#                    the committed golden baseline (scripts/golden-verdict-check.sh)
#
# First run provisions the evaluation template into the data volume
# (one-time ~500 MB clone + mainline fetch + model overlay); later runs
# start from the cached template.
set -uo pipefail

RP="$HOME/RePatch"
DATA="$HOME/data"                    # volume
TEMPLATE="$DATA/template"            # pristine clone + 43-module model
CLONE_PARENT="$DATA/run"             # per-run working copies live here
CLONE="$CLONE_PARENT/kafka"
DATA_REL="data/run"                  # -PdataPath (resolved against $HOME)

DB_HOST="${DB_HOST:-mysql}"
JDBC_USER="${JDBC_USER:-repatch}"
JDBC_PASSWORD="${JDBC_PASSWORD:-repatch}"
RP_DB="${RP_DB:-repatch_run}"
RP_DATASET="${RP_DATASET:-sample}"
RP_TIMEOUT="${RP_TIMEOUT:-5400}"
RP_FORK_URL="${RP_FORK_URL:-https://github.com/danielogen/linkedin}"
RP_MAINLINE_URL="${RP_MAINLINE_URL:-https://github.com/apache/kafka}"

MY=(mysql -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD")

log() { echo "[repatch-container] $*"; }

# ---------------------------------------------------------------- MySQL wait
log "waiting for MySQL at $DB_HOST ..."
for i in $(seq 1 60); do
  mysqladmin ping -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD" --silent 2>/dev/null && break
  sleep 2
  [ "$i" = 60 ] && { log "MySQL never became reachable"; exit 4; }
done
log "MySQL is up"

# ------------------------------------------------------- template provisioning
if [ ! -d "$TEMPLATE/.git" ]; then
  log "provisioning evaluation template (one-time)"
  mkdir -p "$DATA"
  rm -rf "$TEMPLATE.partial"
  git clone "$RP_FORK_URL" "$TEMPLATE.partial" || { log "clone failed"; exit 5; }
  git -C "$TEMPLATE.partial" remote add kafka "$RP_MAINLINE_URL"
  git -C "$TEMPLATE.partial" fetch kafka || { log "mainline fetch failed"; exit 5; }
  # Project-level .idea metadata (modules.xml naming the 43 modules,
  # misc.xml pinning SDK "11").
  tar xzf "$HOME/assets/kafka-model-overlay.tar.gz" -C "$TEMPLATE.partial"
  # Regenerate the *.iml module files IN PLACE with kafka's own gradle:
  # the baked imls carry library jar paths from the machine that generated
  # them; without resolvable library jars the IDE still indexes classes but
  # method-signature resolution silently degrades and every inversion
  # no-ops (vacuous). `gradle idea` (idea plugin injected into every
  # subproject) rewrites them against this container's own dependency
  # cache, which lives in the data volume so the paths stay valid for
  # every later run. Kafka-era gradle needs JDK 11 (baked in the image).
  echo "allprojects { apply plugin: 'idea' }" > "$DATA/inject-idea.gradle"
  log "regenerating module model via gradle idea (downloads kafka deps once)"
  ( cd "$TEMPLATE.partial" && \
    JAVA_HOME=/usr/lib/jvm/temurin-11-jdk-amd64 \
    GRADLE_USER_HOME="$DATA/gradle-kafka" \
    ./gradlew --no-daemon --init-script "$DATA/inject-idea.gradle" idea ) \
    || { log "gradle idea failed — the model would resolve no libraries; aborting"; exit 6; }
  rm -f "$TEMPLATE.partial"/*.ipr "$TEMPLATE.partial"/*.iws
  IMLS=$(find "$TEMPLATE.partial" -name '*.iml' -not -path '*/.git/*' | wc -l)
  [ "$IMLS" -ge 43 ] || { log "expected >=43 imls, got $IMLS; aborting"; exit 6; }
  mv "$TEMPLATE.partial" "$TEMPLATE"
  log "template ready: HEAD=$(git -C "$TEMPLATE" rev-parse --short HEAD), $IMLS imls"
else
  log "template cached: HEAD=$(git -C "$TEMPLATE" rev-parse --short HEAD)"
fi

# --------------------------------------------------------------- per-run state
# Hermetic IDE state: cold-start the sandbox system dirs (persistent VFS /
# workspace caches from a previous run reconcile against the re-created
# clone mid-run and can poison the model load).
for SYS in "$RP"/.intellijPlatform/sandbox/*/*/system; do
  [ -d "$SYS" ] && rm -rf "$SYS" && log "wiped sandbox system $SYS"
done

log "refreshing working clone from template"
mkdir -p "$CLONE_PARENT"
rm -rf "$CLONE"
cp -a "$TEMPLATE" "$CLONE"

# ------------------------------------------------------------- scenario input
GRADLE_DATASET_ARGS=(-PdataSet="$RP_DATASET")
if [ -n "${RP_PRS:-}" ]; then
  # Scenario override: rewrite the sample_data resources (the container's
  # repo copy is disposable) and run in sample mode.
  SD="$RP/src/main/resources/sample_data"
  : > "$SD/repatch_integration_patches"
  IFS=',' read -ra PRLIST <<< "$RP_PRS"
  for PR in "${PRLIST[@]}"; do
    echo "https://github.com/apache/kafka,https://github.com/linkedin/kafka,${PR},MO" >> "$SD/repatch_integration_patches"
  done
  echo "https://github.com/apache/kafka,https://github.com/linkedin/kafka" > "$SD/repatch_integration_projects"
  GRADLE_DATASET_ARGS=(-PdataSet=sample)
  log "scenario override: PRs [$RP_PRS]"
else
  log "dataset: $RP_DATASET"
fi

# --------------------------------------------------------------------- schema
if [ "${RP_KEEP_DB:-0}" != "1" ]; then
  "${MY[@]}" -e "DROP DATABASE IF EXISTS \`$RP_DB\`" 2>/dev/null
fi
sed "s/refactoring_aware_integration_repatch/$RP_DB/g" \
    "$RP/src/main/resources/create_integration_schema.sql" | "${MY[@]}" 2>/dev/null
TABLES=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RP_DB'" 2>/dev/null)
log "database $RP_DB ready ($TABLES tables)"

# ---------------------------------------------------------------------- X11
Xvfb :99 -screen 0 1920x1080x24 >/dev/null 2>&1 &
XVFB_PID=$!
export DISPLAY=:99
trap 'kill "$XVFB_PID" 2>/dev/null' EXIT

# ---------------------------------------------------------------------- run
cd "$RP"

# How many patches this run processes (used for completion detection: the
# IDE can hang on exit AFTER all work and DB writes are complete, so we
# poll patch.is_done and shut the IDE down ourselves once everything is
# recorded — the same early-exit pattern the acceptance harness used).
case "${RP_PRS:+prs}${RP_DATASET}" in
  prs*)     PATCH_FILE="$RP/src/main/resources/sample_data/repatch_integration_patches" ;;
  complete) PATCH_FILE="$RP/src/main/resources/complete_data/repatch_integration_patches" ;;
  *)        PATCH_FILE="$RP/src/main/resources/sample_data/repatch_integration_patches" ;;
esac
EXPECTED=$(grep -c . "$PATCH_FILE")
log "launching pipeline ($EXPECTED patches, timeout ${RP_TIMEOUT}s)"

RUNLOG="$HOME/results/run-$RP_DB.log"
mkdir -p "$HOME/results"
JDBC_USER="$JDBC_USER" JDBC_PASSWORD="$JDBC_PASSWORD" \
JDBC_URL="jdbc:mysql://$DB_HOST/$RP_DB?serverTimezone=UTC" \
JDBC_URL_WITHOUT_DATABASE="jdbc:mysql://$DB_HOST?serverTimezone=UTC" \
  ./gradlew --no-daemon runIde -Pmode=integration -PdataPath="$DATA_REL" \
    -PevaluationProject=linkedin/kafka \
    "${GRADLE_DATASET_ARGS[@]}" \
    -Drepatch.modelBackupDir="$TEMPLATE" > >(tee "$RUNLOG") 2>&1 &
GRADLE_PID=$!

RC=0
DEADLINE=$(( $(date +%s) + RP_TIMEOUT ))
while :; do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    wait "$GRADLE_PID"; RC=$?
    log "pipeline exited on its own rc=$RC"
    break
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    log "timeout after ${RP_TIMEOUT}s — killing pipeline"
    pkill -9 -f ".intellijPlatform/sandbox" 2>/dev/null
    kill -TERM "$GRADLE_PID" 2>/dev/null; wait "$GRADLE_PID" 2>/dev/null
    RC=124
    break
  fi
  DONE=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM patch WHERE is_done=1" "$RP_DB" 2>/dev/null || echo 0)
  if [ "${DONE:-0}" -ge "$EXPECTED" ] && [ "$EXPECTED" -gt 0 ]; then
    log "all $EXPECTED patches recorded done; 45s settle, then shutting the IDE down"
    sleep 45
    pkill -9 -f ".intellijPlatform/sandbox" 2>/dev/null
    kill -TERM "$GRADLE_PID" 2>/dev/null; wait "$GRADLE_PID" 2>/dev/null
    RC=0
    break
  fi
  sleep 10
done

# ---------------------------------------------------------------- run health
# Vacuous inversions mean the refactoring engine silently no-oped and the
# run degraded to a plain git cherry-pick — verdicts still complete, so
# surface it loudly instead of letting it pass as success.
VACUOUS=$(grep -c "Vacuous inversion" "$RUNLOG" 2>/dev/null)
VACUOUS=${VACUOUS:-0}
if [ "${VACUOUS:-0}" -gt 0 ]; then
  echo
  log "WARNING: $VACUOUS vacuous-inversion event(s) — RePatch degraded to plain cherry-pick for those scenarios (see $RUNLOG)"
  RC=$(( RC == 0 ? 42 : RC ))
fi

# ------------------------------------------------------------------- verdicts
echo
echo "==================== VERDICTS ($RP_DB) ===================="
"${MY[@]}" -t -e "
  SELECT p.number, mr.merge_tool,
         CONCAT(mr.total_conflicting_files,'/',mr.total_conflicts,'/',mr.total_conflicting_loc) AS verdict
  FROM patch p
  JOIN merge_commit mc ON mc.patch_id = p.id
  JOIN merge_result mr ON mr.merge_commit_id = mc.id
  ORDER BY p.number, mr.merge_tool;" "$RP_DB" 2>/dev/null
"${MY[@]}" -t -e "
  SELECT p.number, 'clean (no conflict)' AS note
  FROM patch p
  WHERE p.is_done = 1
    AND NOT EXISTS (SELECT 1 FROM merge_commit mc JOIN merge_result mr
                    ON mr.merge_commit_id = mc.id WHERE mc.patch_id = p.id)
  ORDER BY p.number;" "$RP_DB" 2>/dev/null

if [ "${RP_GOLDEN_CHECK:-0}" = "1" ] && [ -z "${RP_PRS:-}" ] && [ "$RP_DATASET" = "sample" ]; then
  echo
  "$RP/scripts/golden-verdict-check.sh" "$RP_DB" -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD"
  GC=$?
  [ $GC -ne 0 ] && RC=$GC
fi

exit $RC
