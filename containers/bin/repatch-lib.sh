# Shared functions for the RePatch container: local MySQL lifecycle,
# one-time template provisioning, pipeline runs, verdict reporting.
# Sourced by the entrypoint and by bin/repatch — not a standalone script.

RP="$HOME/RePatch"
DATA="$HOME/data"                    # volume
TEMPLATE="$DATA/template"            # pristine clone + 43-module model
CLONE_PARENT="$DATA/run"             # per-run working copies live here
# Checkout naming spec: <Owner>-<RepoName> from the fork URL (RepoNaming.java)
CLONE="$CLONE_PARENT/linkedin-kafka"
DATA_REL="data/run"                  # -PdataPath (resolved against $HOME)
RESULTS="$HOME/results"              # volume
LAST_RUN_FILE="$DATA/last-run"
TOKEN_FILE="$DATA/github-token"      # saved GitHub token (data volume)
IMPORT="$HOME/import"                # bind-mount point for user CSV files
SCENARIOS="$DATA/scenarios"          # normalized scenario lists per run db
KAFKA_FORK_URL="https://github.com/linkedin/kafka"   # the modeled target

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
      --bind-address="${RP_DB_BIND:-127.0.0.1}" --port=3306 --mysqlx=OFF --secure-file-priv= \
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

# ---------------------------------------------------------------- GitHub token
# Every scenario fetches PR metadata from api.github.com; anonymous access
# (60 requests/hour) stalls any real run. The token is kept ONLY in the
# data volume (mode 0600) and is never echoed.
load_token() {
  if [ -z "${RP_GITHUB_TOKEN:-}" ] && [ -s "$TOKEN_FILE" ]; then
    RP_GITHUB_TOKEN=$(head -n1 "$TOKEN_FILE" | tr -d '[:space:]')
  fi
  [ -n "${RP_GITHUB_TOKEN:-}" ]
}

save_token() {
  mkdir -p "$DATA"
  ( umask 077; printf '%s
' "$1" > "$TOKEN_FILE" )
}

# Best-effort check against the GitHub API: 0 ok, 1 rejected, 2 unreachable.
check_token() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 \
           -H "Authorization: token $1" https://api.github.com/rate_limit 2>/dev/null)
  case "$code" in 200) return 0 ;; 401) return 1 ;; *) return 2 ;; esac
}

token_status() {
  if load_token; then
    local src="saved in the data volume"
    if [ ! -s "$TOKEN_FILE" ] || [ "$(head -n1 "$TOKEN_FILE" | tr -d '[:space:]')" != "$RP_GITHUB_TOKEN" ]; then
      src="from the RP_GITHUB_TOKEN environment (not saved)"
    fi
    log "GitHub token: present — $src (${#RP_GITHUB_TOKEN} chars, ends ...${RP_GITHUB_TOKEN: -4})"
  else
    log "GitHub token: NONE — anonymous GitHub access is 60 requests/hour; set one with 'repatch token'"
    return 1
  fi
}

# Interactive prompt (input hidden). Returns 0 if a token was saved.
prompt_token() {
  local t
  echo
  echo "A GitHub personal access token is needed to fetch pull-request metadata"
  echo "(anonymous access is limited to 60 requests/hour and stalls real runs)."
  echo "Create one at https://github.com/settings/tokens — no scopes are needed"
  echo "for public repositories. It is stored only in this container's data"
  echo "volume and survives restarts; change it any time with 'repatch token'."
  echo
  read -rs -p "GitHub token (input hidden; press Enter to skip): " t
  echo
  t=$(printf '%s' "$t" | tr -d '[:space:]')
  if [ -z "$t" ]; then
    log "no token saved — runs will use anonymous access until you run 'repatch token'"
    return 1
  fi
  check_token "$t"
  case $? in
    0) log "token verified with api.github.com" ;;
    1) log "WARNING: GitHub rejected this token (401) — saved anyway; replace it with 'repatch token'" ;;
    2) log "could not reach api.github.com to verify the token — saved anyway" ;;
  esac
  save_token "$t"
  RP_GITHUB_TOKEN="$t"; export RP_GITHUB_TOKEN
  log "token saved"
  return 0
}

# ------------------------------------------------------------ repo addressing
# Accepts owner/repo, github.com/owner/repo, https://github.com/owner/repo,
# git@github.com:owner/repo(.git) -> https://github.com/owner/repo
normalize_repo_url() {
  local u="$1"
  u="${u#"${u%%[![:space:]]*}"}"; u="${u%"${u##*[![:space:]]}"}"
  u="${u%/}"; u="${u%.git}"
  u="${u#git@github.com:}"
  u="${u#https://}"; u="${u#http://}"; u="${u#www.}"; u="${u#github.com/}"
  [[ "$u" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || return 1
  echo "https://github.com/$u"
}

# RepoNaming.directoryName in bash: <Owner>-<RepoName> (keep in sync).
repo_dir_name() {
  local u; u=$(normalize_repo_url "$1") || return 1
  u="${u#https://github.com/}"
  echo "${u%%/*}-${u##*/}"
}

# Run-database name for a user-chosen label: MySQL-safe and prefixed with
# repatch_ so `repatch runs` lists it.
db_name_for() {
  local n="${1//-/_}"
  [[ "$n" =~ ^[A-Za-z0-9_]{1,55}$ ]] || { log "bad database name '$1' (letters, digits, _ only)"; return 2; }
  case "$n" in repatch*) ;; *) n="repatch_$n" ;; esac
  echo "$n"
}

# parse_scenarios CSV OUT — user CSV: column 1 = PR number, column 2 =
# source repo (the mainline the PR was opened against), column 3 = target
# repo (the fork receiving the patch); further columns are ignored (free
# for comments). A header row (non-numeric first column) and blank or
# '#' lines are skipped. Writes pipeline lines "<source>,<target>,<pr>,MO"
# (duplicates collapsed, order kept) and prints the scenario count.
parse_scenarios() {
  local csv="$1" out="$2" ln=0 first=1 line pr src tgt rest su tu
  : > "$out.tmp"
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln+1)); line="${line%$'\r'}"
    [[ -z "${line//[[:space:],]/}" || "$line" == \#* ]] && continue
    IFS=',' read -r pr src tgt rest <<< "$line"
    pr="${pr//[[:space:]\"]/}"; src="${src//[[:space:]\"]/}"; tgt="${tgt//[[:space:]\"]/}"
    if ! [[ "$pr" =~ ^[0-9]+$ ]]; then
      if [ "$first" = 1 ]; then first=0; continue; fi      # header row
      log "$csv line $ln: column 1 must be a PR number, got '$pr'"; rm -f "$out.tmp"; return 2
    fi
    first=0
    su=$(normalize_repo_url "$src") || { log "$csv line $ln: bad source repo '$src' (expected owner/repo)"; rm -f "$out.tmp"; return 2; }
    tu=$(normalize_repo_url "$tgt") || { log "$csv line $ln: bad target repo '$tgt' (expected owner/repo)"; rm -f "$out.tmp"; return 2; }
    echo "$su,$tu,$pr,MO" >> "$out.tmp"
  done < "$csv"
  awk '!seen[$0]++' "$out.tmp" > "$out"; rm -f "$out.tmp"
  local n; n=$(grep -c . "$out")
  [ "$n" -gt 0 ] || { log "no scenarios found in $csv"; return 2; }
  echo "$n"
}

# Human summary of a normalized scenario file.
describe_scenarios() {
  local f="$1" pair n tgt mode
  log "scenarios: $(grep -c . "$f")"
  cut -d, -f1,2 "$f" | awk '!s[$0]++' | while IFS=',' read -r src tgt; do
    n=$(grep -c "^$src,$tgt," "$f")
    if [ "$tgt" = "$KAFKA_FORK_URL" ]; then mode="modeled kafka clone"; else mode="paper-parity mode (single-module model)"; fi
    log "  ${src#https://github.com/} -> ${tgt#https://github.com/}: $n PR(s), $mode, checkout $(repo_dir_name "$tgt")"
  done
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

# Provision the non-kafka evaluation clones for paper-parity complete
# runs: clone the fork, add + fetch the mainline remote (cherry-picks
# need its commits), and lay down a minimal .idea skeleton — a single
# whole-repo module. Two reasons: headless 2024.3 refuses to open a
# bare directory (MissingEnvironmentKeyException: project.open.type),
# and a single default module is exactly what the paper's 2020-era
# platform gave these projects, so parity is preserved.
provision_aux_clones() {
  # One-time migration from the pre-spec flat directory names: rename
  # cached clones instead of re-downloading gigabytes.
  local OLD NEW
  for OLD in \
    "ParserGeneratorCC:tulipcc-ParserGeneratorCC" \
    "sqlite-jdbc-crypt:Willena-sqlite-jdbc-crypt" \
    "bitcoinj:bisq-network-bitcoinj" \
    "dogecoinj-new:langerhans-dogecoinj-new" \
    "checker-framework:eisop-checker-framework" \
    "clarin-dspace:ufal-clarin-dspace" \
    "DSpace:DSpace-DSpace" \
  ; do
    NEW="${OLD#*:}"; OLD="${OLD%%:*}"
    if [ -d "$CLONE_PARENT/$OLD/.git" ] && [ ! -d "$CLONE_PARENT/$NEW" ]; then
      mv "$CLONE_PARENT/$OLD" "$CLONE_PARENT/$NEW" && log "migrated clone $OLD -> $NEW"
    fi
  done

  local SPEC DIR FORK RNAME RURL
  for SPEC in \
    "tulipcc-ParserGeneratorCC|https://github.com/tulipcc/ParserGeneratorCC|javacc|https://github.com/javacc/javacc" \
    "Willena-sqlite-jdbc-crypt|https://github.com/Willena/sqlite-jdbc-crypt|sqlite-jdbc|https://github.com/xerial/sqlite-jdbc" \
    "bisq-network-bitcoinj|https://github.com/bisq-network/bitcoinj|bitcoinj|https://github.com/bitcoinj/bitcoinj" \
    "langerhans-dogecoinj-new|https://github.com/langerhans/dogecoinj-new|bitcoinj|https://github.com/bitcoinj/bitcoinj" \
    "eisop-checker-framework|https://github.com/eisop/checker-framework|checker-framework|https://github.com/typetools/checker-framework" \
    "typetools-checker-framework|https://github.com/typetools/checker-framework|checker-framework|https://github.com/eisop/checker-framework" \
    "ufal-clarin-dspace|https://github.com/ufal/clarin-dspace|DSpace|https://github.com/DSpace/DSpace" \
    "DSpace-DSpace|https://github.com/DSpace/DSpace|clarin-dspace|https://github.com/ufal/clarin-dspace" \
    "apache-kafka|https://github.com/apache/kafka|kafka|https://github.com/linkedin/kafka" \
  ; do
    IFS='|' read -r DIR FORK RNAME RURL <<< "$SPEC"
    provision_clone "$DIR" "$FORK" "$RURL" || return $?
  done
}

# provision_clone DIR FORK_URL MAINLINE_URL — clone the fork into
# $CLONE_PARENT/DIR, add the mainline as a remote named after its repo
# (the pipeline's remoteProjectName), fetch it, and lay down the minimal
# single-module .idea if none exists. Idempotent; cached in the volume.
provision_clone() {
  local DIR="$1" FORK="$2" RURL="$3" RNAME="${3##*/}"
  local D="$CLONE_PARENT/$DIR"
  if [ "$FORK" = "$KAFKA_FORK_URL" ]; then
    return 0   # the modeled kafka clone comes from the template
  fi
  mkdir -p "$CLONE_PARENT"
  if [ ! -d "$D/.git" ]; then
    log "provisioning $DIR (one-time clone of $FORK + fetch of $RURL)"
    rm -rf "$D"
    git clone "$FORK" "$D" \
      || { log "ERROR: clone of $FORK failed — aborting before a partial run"; return 7; }
    git -C "$D" remote add "$RNAME" "$RURL"
    local T
    for T in 1 2 3; do
      git -C "$D" fetch "$RNAME" && break
      [ "$T" = 3 ] && { log "ERROR: fetch of $RURL failed 3x — aborting"; return 7; }
      sleep 10
    done
  elif ! git -C "$D" remote get-url "$RNAME" >/dev/null 2>&1; then
    git -C "$D" remote add "$RNAME" "$RURL" && git -C "$D" fetch "$RNAME" \
      || { log "ERROR: could not add/fetch mainline $RURL on $DIR"; return 7; }
  fi
  if [ ! -d "$D/.idea" ]; then
    mkdir -p "$D/.idea"
    cat > "$D/.idea/modules.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://\$PROJECT_DIR\$/$DIR.iml" filepath="\$PROJECT_DIR\$/$DIR.iml" />
    </modules>
  </component>
</project>
EOF
    cat > "$D/$DIR.iml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://\$MODULE_DIR\$">
      <excludeFolder url="file://\$MODULE_DIR\$/.git" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
EOF
    log "wrote minimal single-module .idea for $DIR"
  fi
}

# Verdict tables for one run database.
print_verdicts() {
  local db="$1"
  echo
  echo "==================== VERDICTS ($db) ===================="
  "${MY[@]}" -t -e "
    SELECT SUBSTRING_INDEX(pj.fork_url,'/',-1) AS project, p.number, mr.merge_tool,
           CONCAT(mr.total_conflicting_files,'/',mr.total_conflicts,'/',mr.total_conflicting_loc) AS verdict
    FROM patch p
    JOIN project pj ON p.project_id = pj.id
    JOIN merge_commit mc ON mc.patch_id = p.id
    JOIN merge_result mr ON mr.merge_commit_id = mc.id
    ORDER BY pj.id, p.number, mr.merge_tool;" "$db" 2>/dev/null
  "${MY[@]}" -t -e "
    SELECT SUBSTRING_INDEX(pj.fork_url,'/',-1) AS project, p.number,
           CASE WHEN EXISTS (SELECT 1 FROM merge_commit mc
                             WHERE mc.patch_id = p.id AND mc.project_id = p.project_id
                               AND mc.is_conflicting = 0)
                THEN 'clean (git cherry-pick applies)'
                ELSE 'skipped (no scenario recorded)' END AS note
    FROM patch p
    JOIN project pj ON p.project_id = pj.id
    WHERE p.is_done = 1
      AND NOT EXISTS (SELECT 1 FROM merge_commit mc JOIN merge_result mr
                      ON mr.merge_commit_id = mc.id WHERE mc.patch_id = p.id
                        AND mc.project_id = p.project_id)
    ORDER BY pj.id, p.number;" "$db" 2>/dev/null
}

# One full pipeline run, parameterized by RP_* environment variables
# (see bin/repatch for the interactive flags that set them):
#   RP_PRS       comma-separated kafka PR numbers (overrides the dataset)
#   RP_SCENARIO_FILE  normalized scenario list "<source>,<target>,<pr>,MO"
#                (from `repatch <db> <source> <target> <pr>` / a CSV);
#                overrides RP_PRS and the dataset
#   RP_DATASET   sample | complete           (default: sample)
#   RP_DB        run database name           (default: derived from scenario)
#   RP_KEEP_DB=1 reuse RP_DB instead of dropping and recreating it
#   RP_TIMEOUT   seconds before the run is killed (default: 5400)
#   RP_GOLDEN_CHECK=1  diff a default-sample run against the golden baseline
run_pipeline() {
  RP_DATASET="${RP_DATASET:-sample}"
  local PARITY=0 EVAL_PROJECT="linkedin/kafka" NEED_KAFKA=1
  local SD="$RP/src/main/resources/sample_data"

  # `repatch run <PR...>` is the kafka shortcut: materialize it as a
  # scenario file so every custom run takes the same path.
  if [ -n "${RP_PRS:-}" ] && [ -z "${RP_SCENARIO_FILE:-}" ]; then
    mkdir -p "$SCENARIOS"
    RP_SCENARIO_FILE="$SCENARIOS/prs-${RP_PRS//,/_}.txt"; RP_SCENARIO_FILE="${RP_SCENARIO_FILE:0:200}"
    : > "$RP_SCENARIO_FILE"
    local PR
    IFS=',' read -ra PRLIST <<< "$RP_PRS"
    for PR in "${PRLIST[@]}"; do
      echo "https://github.com/apache/kafka,$KAFKA_FORK_URL,${PR},MO" >> "$RP_SCENARIO_FILE"
    done
  fi

  if [ -n "${RP_SCENARIO_FILE:-}" ]; then
    local N; N=$(grep -c . "$RP_SCENARIO_FILE")
    # ~15 min per scenario, never less than the sample default.
    RP_TIMEOUT="${RP_TIMEOUT:-$(( N * 900 > 5400 ? N * 900 : 5400 ))}"
    # The modeled kafka template is only needed when linkedin/kafka is a target.
    grep -q ",$KAFKA_FORK_URL," "$RP_SCENARIO_FILE" || NEED_KAFKA=0
  elif [ "$RP_DATASET" = "complete" ]; then
    RP_TIMEOUT="${RP_TIMEOUT:-172800}"   # 477 scenarios: two days
  else
    RP_TIMEOUT="${RP_TIMEOUT:-5400}"
  fi

  # GitHub token: saved in the data volume by the first-launch prompt /
  # `repatch token`, or supplied via --token / RP_GITHUB_TOKEN.
  load_token || true
  if [ -n "${RP_GITHUB_TOKEN:-}" ]; then
    printf 'OAuthToken=%s\n' "$RP_GITHUB_TOKEN" \
      > "$RP/src/main/resources/github-oauth.properties"
    log "GitHub token installed"
  else
    log "WARNING: no GitHub token — anonymous API access (60 req/h) will stall runs; see 'repatch token'"
  fi
  if [ -z "${RP_DB:-}" ]; then
    if [ -n "${RP_PRS:-}" ]; then
      RP_DB="repatch_pr${RP_PRS//,/_}"; RP_DB="${RP_DB:0:60}"
    else
      RP_DB="repatch_${RP_DATASET}"
    fi
  fi

  if [ "$NEED_KAFKA" = 1 ]; then
    ensure_template || return $?
  else
    log "no linkedin/kafka target in this run — skipping the kafka template"
  fi

  # Hermetic IDE state: cold-start the sandbox system dirs (persistent
  # VFS / workspace caches from a previous run reconcile against the
  # re-created clone mid-run and can poison the model load).
  local SYS
  for SYS in "$RP"/.intellijPlatform/sandbox/*/*/system; do
    [ -d "$SYS" ] && rm -rf "$SYS" && log "wiped sandbox system $SYS"
  done

  mkdir -p "$CLONE_PARENT"
  # pre-naming-spec working copy; recreated from the template as linkedin-kafka
  [ -d "$CLONE_PARENT/kafka" ] && rm -rf "$CLONE_PARENT/kafka" && log "removed legacy clone dir kafka"
  if [ "$NEED_KAFKA" = 1 ]; then
    log "refreshing working clone from template"
    rm -rf "$CLONE"
    cp -a "$TEMPLATE" "$CLONE"
  fi

  # Auxiliary clones (non-kafka projects auto-cloned by a previous
  # complete run) persist in the volume; clear any leftover merge state
  # so this run starts from clean checkouts.
  local AUX
  for AUX in "$CLONE_PARENT"/*/; do
    AUX="${AUX%/}"
    [ "$AUX" = "$CLONE" ] && continue
    [ -d "$AUX/.git" ] || continue
    git -C "$AUX" cherry-pick --abort >/dev/null 2>&1
    git -C "$AUX" reset --hard -q >/dev/null 2>&1
    git -C "$AUX" clean -qfd -e .idea -e '*.iml' >/dev/null 2>&1
    log "reset auxiliary clone $(basename "$AUX")"
  done

  local GRADLE_DATASET_ARGS=(-PdataSet="$RP_DATASET")
  if [ -n "${RP_SCENARIO_FILE:-}" ]; then
    # Custom scenarios: rewrite the sample_data resources (the container's
    # repo copy is disposable) and run in sample mode. Every non-kafka
    # target is cloned at first use with the minimal single-module model
    # (paper-parity mode); linkedin/kafka uses the modeled template.
    cp "$RP_SCENARIO_FILE" "$SD/repatch_integration_patches"
    cut -d, -f1,2 "$SD/repatch_integration_patches" | awk '!s[$0]++' > "$SD/repatch_integration_projects"
    GRADLE_DATASET_ARGS=(-PdataSet=sample)
    EVAL_PROJECT="github.com"
    describe_scenarios "$RP_SCENARIO_FILE"
    local SRC TGT
    while IFS=',' read -r SRC TGT; do
      [ "$TGT" = "$KAFKA_FORK_URL" ] && continue
      PARITY=1
      provision_clone "$(repo_dir_name "$TGT")" "$TGT" "$SRC" || return $?
    done < "$SD/repatch_integration_projects"
  elif [ "$RP_DATASET" = "complete" ]; then
    # The paper's complete list: 478 scenario lines (477 distinct) across
    # 6 repository families in 10 direction pairs — ALL of them run.
    # kafka mainline->fork runs with the provisioned clone + regenerated
    # module model (the honest engine). Everything else — including the
    # reverse kafka direction — runs in PAPER-PARITY mode: cloned at
    # first use with a minimal single-module model, the same environment
    # the paper artifact gave these projects; the engine's refactoring
    # machinery may partially no-op there and the instrumentation
    # reports it instead of hiding it. Owner-prefixed checkout naming
    # (<Owner>-<RepoName>, RepoNaming.java) plus exact fork-URL patch
    # matching make both directions of one repository coexist.
    PARITY=1
    cp "$RP/src/main/resources/complete_data/repatch_integration_patches" \
       "$SD/repatch_integration_patches"
    # Project order: small projects first (fast feedback), kafka last.
    printf '%s\n' \
      "https://github.com/javacc/javacc,https://github.com/tulipcc/ParserGeneratorCC" \
      "https://github.com/xerial/sqlite-jdbc,https://github.com/Willena/sqlite-jdbc-crypt" \
      "https://github.com/bitcoinj/bitcoinj,https://github.com/bisq-network/bitcoinj" \
      "https://github.com/bitcoinj/bitcoinj,https://github.com/langerhans/dogecoinj-new" \
      "https://github.com/typetools/checker-framework,https://github.com/eisop/checker-framework" \
      "https://github.com/eisop/checker-framework,https://github.com/typetools/checker-framework" \
      "https://github.com/DSpace/DSpace,https://github.com/ufal/clarin-dspace" \
      "https://github.com/ufal/clarin-dspace,https://github.com/DSpace/DSpace" \
      "https://github.com/linkedin/kafka,https://github.com/apache/kafka" \
      "https://github.com/apache/kafka,https://github.com/linkedin/kafka" \
      > "$SD/repatch_integration_projects"
    # Sanity: every scenario's project pair must be in the projects file.
    local MISSING
    MISSING=$(cut -d, -f1,2 "$SD/repatch_integration_patches" | sort -u \
              | grep -vxF -f "$SD/repatch_integration_projects" | head -3)
    [ -n "$MISSING" ] && log "WARNING: scenario pairs missing from projects file: $MISSING"
    GRADLE_DATASET_ARGS=(-PdataSet=sample)
    EVAL_PROJECT="github.com"   # substring-matches every project line
    log "dataset: complete — $(cut -d, -f2,3 "$SD/repatch_integration_patches" | sort -u | grep -c .) scenarios (393 kafka modeled + 84 in paper-parity mode)"
  else
    log "dataset: $RP_DATASET"
  fi

  # Paper-parity runs need the non-kafka clones (with their minimal
  # .idea skeletons) in place BEFORE the pipeline reaches them.
  if [ "$PARITY" = "1" ]; then
    provision_aux_clones || return $?
  fi

  if [ "${RP_KEEP_DB:-0}" != "1" ]; then
    "${MY[@]}" -e "DROP DATABASE IF EXISTS \`$RP_DB\`" 2>/dev/null
  fi
  # The schema script contains DROP TABLE statements — applying it to an
  # existing database would wipe the very data --keep-db promises to
  # keep. Apply it only when the tables are absent.
  local TABLES
  TABLES=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RP_DB'" 2>/dev/null)
  if [ "${TABLES:-0}" -eq 0 ]; then
    sed "s/refactoring_aware_integration_repatch/$RP_DB/g" \
        "$RP/src/main/resources/create_integration_schema.sql" | "${MY[@]}" 2>/dev/null
    TABLES=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RP_DB'" 2>/dev/null)
  fi
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
  # Distinct (fork, PR) pairs — duplicate lines collapse to one DB row.
  EXPECTED=$(cut -d, -f2,3 "$PATCH_FILE" | sort -u | grep -c .)
  log "launching pipeline ($EXPECTED patches, timeout ${RP_TIMEOUT}s)"

  local RUNLOG="$RESULTS/run-$RP_DB.log"
  mkdir -p "$RESULTS"
  JDBC_USER="$JDBC_USER" JDBC_PASSWORD="$JDBC_PASSWORD" \
  JDBC_URL="jdbc:mysql://$DB_HOST/$RP_DB?serverTimezone=UTC" \
  JDBC_URL_WITHOUT_DATABASE="jdbc:mysql://$DB_HOST?serverTimezone=UTC" \
    ./gradlew --no-daemon runIde -Pmode=integration -PdataPath="$DATA_REL" \
      -PevaluationProject="$EVAL_PROJECT" \
      "${GRADLE_DATASET_ARGS[@]}" \
      -Drepatch.modelBackupDir="$TEMPLATE" > >(tee "$RUNLOG") 2>&1 &
  local GRADLE_PID=$!

  local RC=0 DEADLINE DONE
  DEADLINE=$(( $(date +%s) + RP_TIMEOUT ))
  while :; do
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
      wait "$GRADLE_PID"; RC=$?
      log "pipeline exited on its own rc=$RC"
      # A crash inside the project loop still exits 0 from the IDE:
      # detect an incomplete run rather than reporting silent success.
      DONE=$("${MY[@]}" -N -e "SELECT COUNT(*) FROM patch WHERE is_done=1" "$RP_DB" 2>/dev/null || echo 0)
      if [ "$RC" -eq 0 ] && [ "${DONE:-0}" -lt "$EXPECTED" ]; then
        log "WARNING: pipeline exited with only ${DONE:-0}/$EXPECTED scenarios done — incomplete run"
        RC=3
      fi
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
    if [ "$PARITY" = "1" ]; then
      # Paper-parity runs include model-less projects where the engine
      # is expected to partially no-op; report loudly but don't fail.
      log "NOTE: $VACUOUS vacuous-inversion event(s) — expected for"
      log "model-less non-kafka projects (paper-parity mode); if any occur"
      log "during linkedin/kafka scenarios that is a real problem (see $RUNLOG)"
    else
      log "WARNING: $VACUOUS vacuous-inversion event(s) — RePatch degraded to plain cherry-pick for those scenarios (see $RUNLOG)"
      RC=$(( RC == 0 ? 42 : RC ))
    fi
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
 MySQL runs inside this container; databases, clones and
 your GitHub token persist in the 'data' volume.

   repatch run                    golden 5-patch sample (smoke test)
   repatch run --golden-check     sample + diff vs the golden baseline
   repatch run --dataset complete the paper's FULL evaluation (477
                                  scenarios, ~15-30h; resume --keep-db)

   repatch <db> <source> <target> <pr>
                                  run ONE scenario: PR <pr> opened
                                  against <source> (owner/repo), applied
                                  to the fork <target>, results in
                                  database repatch_<db>
   repatch <db> <file.csv>        run every scenario in a CSV: columns
                                  pr, source, target (extra columns are
                                  ignored). Put files in /home/repatch/
                                  import (docker -v or docker cp).
        options: --dry-run (show the plan only) --keep-db (resume)
                 --timeout SECONDS --token TOKEN

   repatch token                  set / verify the GitHub token
                                  (--status, --clear)
   repatch runs                   list past run databases
   repatch verdicts [db]          verdict table of a run
   repatch log [db]               page through a run's log
   repatch results [PR] [db]      locate merged result trees
   repatch validate [--db NAME]   validation study levels 2-3: build +
                                  test baseline and RePatch states
                                  (--prs n,n --test-scope auto|module|
                                  full|none --jdk 8|11|17 --limit N
                                  --records --status --redo)
   repatch report [db]            study tables/figures from the dataset
   repatch sql [db]               open a mysql shell
   repatch status                 provisioning / health check
   repatch help                   this text
============================================================
EOF
}
