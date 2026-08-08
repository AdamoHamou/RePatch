# Shared functions for the RePatch container: local MySQL lifecycle,
# one-time template provisioning, pipeline runs, verdict reporting.
# Sourced by the entrypoint and by bin/repatch — not a standalone script.

RP="$HOME/RePatch"
DATA="$HOME/data"                    # volume
TEMPLATE="$DATA/template"            # pristine clone + 43-module model
CLONE_PARENT="$DATA/run"             # per-run working copies live here
CLONE="$CLONE_PARENT/kafka"
DATA_REL="data/run"                  # -PdataPath (resolved against $HOME)
RESULTS="$HOME/results"              # volume
LAST_RUN_FILE="$DATA/last-run"

MYSQL_DATA="$DATA/mysql"             # MySQL lives in the same container;
MYSQL_SOCK="$MYSQL_DATA/mysql.sock"  # its datadir persists in the volume
MYSQLD=/usr/sbin/mysqld

DB_HOST="${DB_HOST:-127.0.0.1}"
JDBC_USER="${JDBC_USER:-repatch}"
JDBC_PASSWORD="${JDBC_PASSWORD:-repatch}"

MY=(mysql -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD")
MYROOT=(mysql --no-defaults -uroot -S "$MYSQL_SOCK")

log() { echo "[repatch] $*"; }

mysql_up() {
  mysqladmin ping -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD" --silent >/dev/null 2>&1
}

# Start (initializing on first boot) the container-local MySQL server.
# If DB_HOST points somewhere else, just wait for that server instead.
start_mysql() {
  mysql_up && return 0
  if [ "$DB_HOST" != "127.0.0.1" ] && [ "$DB_HOST" != "localhost" ]; then
    log "waiting for external MySQL at $DB_HOST ..."
    for i in $(seq 1 60); do
      mysql_up && { log "MySQL is up"; return 0; }
      sleep 2
    done
    log "MySQL at $DB_HOST never became reachable"; return 4
  fi
  if [ ! -d "$MYSQL_DATA/mysql" ]; then
    log "initializing MySQL data directory (one-time, persists in the data volume)"
    mkdir -p "$MYSQL_DATA"
    "$MYSQLD" --no-defaults --initialize-insecure \
        --datadir="$MYSQL_DATA" --log-error="$MYSQL_DATA/error.log" \
      || { log "mysqld --initialize failed (see $MYSQL_DATA/error.log)"; return 4; }
  fi
  log "starting MySQL"
  rm -f "$MYSQL_SOCK" "$MYSQL_SOCK.lock"
  "$MYSQLD" --no-defaults --daemonize \
      --datadir="$MYSQL_DATA" --socket="$MYSQL_SOCK" \
      --pid-file="$MYSQL_DATA/mysqld.pid" --log-error="$MYSQL_DATA/error.log" \
      --bind-address=127.0.0.1 --port=3306 --mysqlx=OFF --secure-file-priv= \
      2>/dev/null \
    || { log "mysqld failed to start (see $MYSQL_DATA/error.log)"; return 4; }
  "${MYROOT[@]}" -e "CREATE USER IF NOT EXISTS '$JDBC_USER'@'%' IDENTIFIED BY '$JDBC_PASSWORD';
                     GRANT ALL PRIVILEGES ON *.* TO '$JDBC_USER'@'%'; FLUSH PRIVILEGES;" \
    || { log "could not provision the '$JDBC_USER' MySQL account"; return 4; }
  for i in $(seq 1 30); do
    mysql_up && { log "MySQL is up"; return 0; }
    sleep 1
  done
  log "MySQL never became reachable"; return 4
}

shutdown_mysql() {
  [ -S "$MYSQL_SOCK" ] && mysqladmin --no-defaults -uroot -S "$MYSQL_SOCK" shutdown 2>/dev/null
  return 0
}

# One-time template provisioning: clone the evaluation fork, fetch the
# mainline, lay down the .idea metadata, and regenerate the 43 *.iml
# module files with kafka's own `gradle idea` so their library jar paths
# point at THIS container's dependency cache (baked imls from another
# machine reference jars that don't exist here, and without resolvable
# libraries every inversion silently no-ops).
ensure_template() {
  RP_FORK_URL="${RP_FORK_URL:-https://github.com/danielogen/linkedin}"
  RP_MAINLINE_URL="${RP_MAINLINE_URL:-https://github.com/apache/kafka}"
  if [ -d "$TEMPLATE/.git" ]; then
    log "template cached: HEAD=$(git -C "$TEMPLATE" rev-parse --short HEAD)"
    return 0
  fi
  log "provisioning evaluation template (one-time, ~500 MB download)"
  mkdir -p "$DATA"
  rm -rf "$TEMPLATE.partial"
  git clone "$RP_FORK_URL" "$TEMPLATE.partial" || { log "clone failed"; return 5; }
  git -C "$TEMPLATE.partial" remote add kafka "$RP_MAINLINE_URL"
  git -C "$TEMPLATE.partial" fetch kafka || { log "mainline fetch failed"; return 5; }
  # Project-level .idea metadata (modules.xml naming the 43 modules,
  # misc.xml pinning SDK "11").
  tar xzf "$HOME/assets/kafka-model-overlay.tar.gz" -C "$TEMPLATE.partial"
  echo "allprojects { apply plugin: 'idea' }" > "$DATA/inject-idea.gradle"
  log "regenerating module model via gradle idea (downloads kafka deps once)"
  ( cd "$TEMPLATE.partial" && \
    JAVA_HOME=/usr/lib/jvm/temurin-11-jdk-amd64 \
    GRADLE_USER_HOME="$DATA/gradle-kafka" \
    ./gradlew --no-daemon --init-script "$DATA/inject-idea.gradle" idea ) \
    || { log "gradle idea failed — the model would resolve no libraries; aborting"; return 6; }
  rm -f "$TEMPLATE.partial"/*.ipr "$TEMPLATE.partial"/*.iws
  local imls
  imls=$(find "$TEMPLATE.partial" -name '*.iml' -not -path '*/.git/*' | wc -l)
  [ "$imls" -ge 43 ] || { log "expected >=43 imls, got $imls; aborting"; return 6; }
  mv "$TEMPLATE.partial" "$TEMPLATE"
  log "template ready: HEAD=$(git -C "$TEMPLATE" rev-parse --short HEAD), $imls imls"
}

# Verdict tables for one run database.
print_verdicts() {
  local db="$1"
  echo
  echo "==================== VERDICTS ($db) ===================="
  "${MY[@]}" -t -e "
    SELECT p.number, mr.merge_tool,
           CONCAT(mr.total_conflicting_files,'/',mr.total_conflicts,'/',mr.total_conflicting_loc) AS verdict
    FROM patch p
    JOIN merge_commit mc ON mc.patch_id = p.id
    JOIN merge_result mr ON mr.merge_commit_id = mc.id
    ORDER BY p.number, mr.merge_tool;" "$db" 2>/dev/null
  "${MY[@]}" -t -e "
    SELECT p.number, 'clean (no conflict)' AS note
    FROM patch p
    WHERE p.is_done = 1
      AND NOT EXISTS (SELECT 1 FROM merge_commit mc JOIN merge_result mr
                      ON mr.merge_commit_id = mc.id WHERE mc.patch_id = p.id)
    ORDER BY p.number;" "$db" 2>/dev/null
}

# One full pipeline run, parameterized by RP_* environment variables
# (see bin/repatch for the interactive flags that set them):
#   RP_PRS       comma-separated kafka PR numbers (overrides the dataset)
#   RP_DATASET   sample | complete           (default: sample)
#   RP_DB        run database name           (default: derived from scenario)
#   RP_KEEP_DB=1 reuse RP_DB instead of dropping and recreating it
#   RP_TIMEOUT   seconds before the run is killed (default: 5400)
#   RP_GOLDEN_CHECK=1  diff a default-sample run against the golden baseline
run_pipeline() {
  RP_DATASET="${RP_DATASET:-sample}"
  # A complete run processes 393 PRs; give it a day by default.
  if [ "$RP_DATASET" = "complete" ] && [ -z "${RP_PRS:-}" ]; then
    RP_TIMEOUT="${RP_TIMEOUT:-86400}"
  else
    RP_TIMEOUT="${RP_TIMEOUT:-5400}"
  fi

  # GitHub token (strongly recommended for large runs: every patch fetches
  # PR metadata, and anonymous access is limited to 60 requests/hour).
  if [ -n "${RP_GITHUB_TOKEN:-}" ]; then
    printf 'OAuthToken=%s\n' "$RP_GITHUB_TOKEN" \
      > "$RP/src/main/resources/github-oauth.properties"
    log "GitHub token installed from RP_GITHUB_TOKEN"
  fi
  if [ -z "${RP_DB:-}" ]; then
    if [ -n "${RP_PRS:-}" ]; then
      RP_DB="repatch_pr${RP_PRS//,/_}"; RP_DB="${RP_DB:0:60}"
    else
      RP_DB="repatch_${RP_DATASET}"
    fi
  fi

  ensure_template || return $?

  # Hermetic IDE state: cold-start the sandbox system dirs (persistent
  # VFS / workspace caches from a previous run reconcile against the
  # re-created clone mid-run and can poison the model load).
  local SYS
  for SYS in "$RP"/.intellijPlatform/sandbox/*/*/system; do
    [ -d "$SYS" ] && rm -rf "$SYS" && log "wiped sandbox system $SYS"
  done

  log "refreshing working clone from template"
  mkdir -p "$CLONE_PARENT"
  rm -rf "$CLONE"
  cp -a "$TEMPLATE" "$CLONE"

  local GRADLE_DATASET_ARGS=(-PdataSet="$RP_DATASET")
  local SD="$RP/src/main/resources/sample_data"
  if [ -n "${RP_PRS:-}" ]; then
    # Scenario override: rewrite the sample_data resources (the
    # container's repo copy is disposable) and run in sample mode.
    local PR
    : > "$SD/repatch_integration_patches"
    IFS=',' read -ra PRLIST <<< "$RP_PRS"
    for PR in "${PRLIST[@]}"; do
      echo "https://github.com/apache/kafka,https://github.com/linkedin/kafka,${PR},MO" >> "$SD/repatch_integration_patches"
    done
    echo "https://github.com/apache/kafka,https://github.com/linkedin/kafka" > "$SD/repatch_integration_projects"
    GRADLE_DATASET_ARGS=(-PdataSet=sample)
    log "scenario override: PRs [$RP_PRS]"
  elif [ "$RP_DATASET" = "complete" ]; then
    # The paper's complete list spans 6 project pairs, but only the kafka
    # pair has a provisioned clone with a regenerated module model — the
    # other projects would auto-clone model-less and every inversion would
    # silently no-op (the degradation mode this image exists to prevent).
    # Filter to the kafka mainline->fork scenarios (393 unique PRs, the
    # paper's kafka evaluation) and run them through the sample machinery.
    grep 'apache/kafka,https://github.com/linkedin/kafka' \
      "$RP/src/main/resources/complete_data/repatch_integration_patches" \
      > "$SD/repatch_integration_patches"
    echo "https://github.com/apache/kafka,https://github.com/linkedin/kafka" > "$SD/repatch_integration_projects"
    GRADLE_DATASET_ARGS=(-PdataSet=sample)
    log "dataset: complete — $(grep -c . "$SD/repatch_integration_patches") kafka scenarios (non-kafka projects need model provisioning; not yet supported)"
  else
    log "dataset: $RP_DATASET"
  fi

  if [ "${RP_KEEP_DB:-0}" != "1" ]; then
    "${MY[@]}" -e "DROP DATABASE IF EXISTS \`$RP_DB\`" 2>/dev/null
  fi
  sed "s/refactoring_aware_integration_repatch/$RP_DB/g" \
      "$RP/src/main/resources/create_integration_schema.sql" | "${MY[@]}" 2>/dev/null
  local TABLES
  TABLES=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RP_DB'" 2>/dev/null)
  log "database $RP_DB ready ($TABLES tables)"
  echo "$RP_DB" > "$LAST_RUN_FILE"

  # Headless X server, shared by every run in this container session.
  if ! pgrep -x Xvfb >/dev/null 2>&1; then
    Xvfb :99 -screen 0 1920x1080x24 >/dev/null 2>&1 &
  fi
  export DISPLAY=:99

  cd "$RP" || return 1

  # How many patches this run processes (used for completion detection:
  # the IDE can hang on exit AFTER all work and DB writes are complete,
  # so we poll patch.is_done and shut the IDE down ourselves once
  # everything is recorded).
  # All three modes run through sample_data (PRS and complete rewrite it).
  local PATCH_FILE="$SD/repatch_integration_patches"
  local EXPECTED
  EXPECTED=$(grep -c . "$PATCH_FILE")
  log "launching pipeline ($EXPECTED patches, timeout ${RP_TIMEOUT}s)"

  local RUNLOG="$RESULTS/run-$RP_DB.log"
  mkdir -p "$RESULTS"
  JDBC_USER="$JDBC_USER" JDBC_PASSWORD="$JDBC_PASSWORD" \
  JDBC_URL="jdbc:mysql://$DB_HOST/$RP_DB?serverTimezone=UTC" \
  JDBC_URL_WITHOUT_DATABASE="jdbc:mysql://$DB_HOST?serverTimezone=UTC" \
    ./gradlew --no-daemon runIde -Pmode=integration -PdataPath="$DATA_REL" \
      -PevaluationProject=linkedin/kafka \
      "${GRADLE_DATASET_ARGS[@]}" \
      -Drepatch.modelBackupDir="$TEMPLATE" > >(tee "$RUNLOG") 2>&1 &
  local GRADLE_PID=$!

  local RC=0 DEADLINE DONE
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

  # Vacuous inversions mean the refactoring engine silently no-oped and
  # the run degraded to a plain git cherry-pick — verdicts still
  # complete, so surface it loudly instead of letting it pass as success.
  local VACUOUS
  VACUOUS=$(grep -c "Vacuous inversion" "$RUNLOG" 2>/dev/null)
  VACUOUS=${VACUOUS:-0}
  if [ "$VACUOUS" -gt 0 ]; then
    echo
    log "WARNING: $VACUOUS vacuous-inversion event(s) — RePatch degraded to plain cherry-pick for those scenarios (see $RUNLOG)"
    RC=$(( RC == 0 ? 42 : RC ))
  fi

  print_verdicts "$RP_DB"

  if [ "${RP_GOLDEN_CHECK:-0}" = "1" ] && [ -z "${RP_PRS:-}" ] && [ "$RP_DATASET" = "sample" ]; then
    echo
    "$RP/scripts/golden-verdict-check.sh" "$RP_DB" -h"$DB_HOST" -u"$JDBC_USER" -p"$JDBC_PASSWORD"
    local GC=$?
    [ $GC -ne 0 ] && RC=$GC
  fi

  return $RC
}

banner() {
  cat <<'EOF'
============================================================
 RePatch 2.0 — interactive evaluation container
 MySQL runs inside this container; databases and the kafka
 clone persist in the 'data' volume across sessions.

   repatch run                    golden 5-patch sample set
   repatch run 16954              one kafka PR (or a list)
   repatch run --dataset complete the paper's 393 kafka PRs (~12-24h;
                                  use --token <github-token>, resume
                                  after interruption with --keep-db)
   repatch run --golden-check     sample + golden baseline diff
   repatch runs                   list past run databases
   repatch verdicts [db]          verdict table of a run
   repatch log [db]               page through a run's log
   repatch results [PR]           locate merged result trees
   repatch sql [db]               open a mysql shell
   repatch status                 provisioning / health check
   repatch help                   this text

 Type `exit` to leave (MySQL shuts down cleanly).
============================================================
EOF
}
