# RePatch 2.0 — containerized evaluation

One self-contained image: the RePatch pipeline (IntelliJ 2024.3.7 /
JDK 17), MySQL 8, and an interactive `repatch` CLI — running headlessly
on Ubuntu 24.04, so it works the same on virtually any machine with
Docker. No sidecar containers, no compose networking: start it, get a
shell, run evaluations.

Contents: [Requirements](#requirements) · [Build](#1-build-the-image-once) ·
[Launch](#2-launch) · [Command reference](#3-command-reference) ·
[Your own scenarios](#4-running-your-own-scenarios) ·
[GitHub token](#5-github-token) · [Browsing databases](#6-browsing-the-databases) ·
[What persists](#7-what-persists) · [Troubleshooting](#8-troubleshooting) ·
[Full paper run](#the-full-paper-run) · [Validation study](#the-validation-study-build--test-stages)

## Requirements

- Docker (Linux) or Docker Desktop (Windows/macOS; WSL 2 backend on
  Windows). **Docker Desktop must be running** before any `docker`
  command works.
- ~20 GB free disk (5.5 GB image + build layers + kafka clone,
  dependency caches, and MySQL data in a volume) and ~10 GB RAM
  available to Docker (Docker Desktop → Settings → Resources, or
  `%UserProfile%\.wslconfig` on Windows).
- A GitHub personal access token with **no scopes** (see [§5](#5-github-token)).

## 1. Build the image (once)

The image is built locally from the repository — it is not stored in
git, so a fresh clone always needs one build (~10 min, downloads all
dependencies). Rebuild only after pulling changes to `containers/` or
the pipeline source.

```bash
git clone -b upgrade-2.0/spec1-conflicting-files https://github.com/AdamoHamou/RePatch.git
cd RePatch
docker build -f containers/Dockerfile -t repatch-headless .
```

Check: `docker images repatch-headless` lists the image (Docker Desktop →
**Images** tab; nothing appears under *Containers* until you run it).

## 2. Launch

Two ways to run it. Both use the same two named volumes, so databases,
clones and your token are shared and persist either way.

### (a) One-off interactive session

```bash
docker run -it --rm \
  -v repatch-data:/home/repatch/data \
  -v repatch-results:/home/repatch/results \
  -v "$PWD/containers/import":/home/repatch/import \
  --memory 10g --shm-size 1g \
  repatch-headless
```

Windows PowerShell (one line, or with backticks):

```powershell
docker run -it --rm -v repatch-data:/home/repatch/data -v repatch-results:/home/repatch/results -v ${PWD}\containers\import:/home/repatch/import --memory 10g --shm-size 1g repatch-headless
```

You get a shell with MySQL already running. The first launch asks for
your GitHub token. `exit` leaves; `--rm` discards the container but the
volumes keep everything.

### (b) Always-on container + terminals on demand (recommended for a course)

Start the container once in the background with a keep-alive command,
then open as many terminals into it as you like with `docker exec`:

```powershell
docker network create repatch-net      # one-time

docker run -d --name repatch --restart unless-stopped --network repatch-net `
  -e RP_DB_BIND=0.0.0.0 `
  -v repatch-data:/home/repatch/data -v repatch-results:/home/repatch/results `
  -v ${PWD}\containers\import:/home/repatch/import `
  --memory 10g --shm-size 1g `
  repatch-headless sleep infinity

docker exec -it repatch bash           # a terminal, any time (or Docker Desktop → Exec tab)
```

(Linux/macOS: same command with `\` line continuations and
`"$PWD/containers/import"`.) The first time, run `repatch token` inside
to save your GitHub token — the automatic prompt only fires in mode (a).
`--restart unless-stopped` brings it back whenever Docker starts;
`docker stop repatch` / `docker start repatch` control it deliberately.
`-e RP_DB_BIND=0.0.0.0` lets phpMyAdmin reach MySQL (see [§6](#6-browsing-the-databases)).

**Only one repatch container can hold the data volume at a time** — with
(b) running, use `docker exec`, don't start a second `docker run`.

### Compose shortcut

```bash
cd containers
docker compose build
docker compose run --rm headless                     # same as (a); import/ mounted automatically
docker compose run --rm headless repatch run 16954   # one-shot command
```

### Non-interactive / CI

Any arguments after the image name run as-is (MySQL is started first);
with **no TTY and no arguments** the container performs one default
sample run, so the old one-shot workflow still works:

```bash
docker run --rm -v repatch-data:/home/repatch/data repatch-headless \
  repatch run --golden-check          # exit code ≠ 0 on baseline mismatch
```

## 3. Command reference

Everything is driven by the `repatch` CLI inside the container
(`repatch help` prints this list).

### Running evaluations

| Command | What it does |
|---|---|
| `repatch run` | The golden 5-patch kafka sample — the smoke test. First run provisions the kafka evaluation clone (one-time ~500 MB + dependency download). |
| `repatch run --golden-check` | Sample run + diff against the golden baseline verdicts; non-zero exit on mismatch. |
| `repatch run 16954` / `repatch run 12363,15889` | Kafka shortcut: the given apache/kafka PR(s) into linkedin/kafka. Database `repatch_pr<N>`. |
| `repatch run --dataset complete` | The paper's full evaluation, all 477 scenarios (~15–30 h, ~150 GB). See [The full paper run](#the-full-paper-run). |
| `repatch <db> <source> <target> <pr>` | **One scenario of your choosing** — PR `<pr>` opened against `<source>`, integrated into the fork `<target>`; results in database `repatch_<db>`. |
| `repatch <db> <file.csv>` | **Every scenario listed in a CSV** (columns: pr, source, target; extra columns ignored). See [§4](#4-running-your-own-scenarios). |

Flags for `run` and custom runs: `--dry-run` (custom runs: print the
plan and exit), `--keep-db` (resume — scenarios already done are
skipped), `--timeout SECONDS`, `--token TOKEN` (one-off override),
`--db NAME` (`run` only: name the database). `run` also accepts
`--fork URL` / `--mainline URL` to provision a different modeled clone.

Exit codes: `0` ok · `42` run completed but at least one
vacuous-inversion event (engine degraded to plain cherry-pick — check
the log) · `3` pipeline exited before every scenario was recorded ·
`124` timeout · `2` usage error.

### Inspecting results

| Command | What it does |
|---|---|
| `repatch runs` | List every run database with patches done / total. |
| `repatch verdicts [db]` | Verdict table of a run — conflicting files / conflict blocks / conflicting LOC for RePatch and git cherry-pick per PR, plus clean cherry-picks. Default: the latest run. |
| `repatch log [db]` | Page through the run's full pipeline log (`less`). |
| `repatch results [PR] [db]` | Locate the merged result trees on disk for a PR (conflict markers left in place; `repatch-state.bundle` holds the full RePatch-produced state). No PR: list the results directory. |
| `repatch sql [db]` | Open a `mysql` shell on the run database (`SHOW TABLES;`, `SELECT * FROM merge_result;` …). |
| `repatch status` | Health check: MySQL up? kafka template provisioned? volume sizes, number of run databases. |

### Validation study (build + test stages)

| Command | What it does |
|---|---|
| `repatch validate [--db NAME]` | For every conflict-free case of a run: build and test the baseline target and the RePatch-produced state; classify VALID / INTEGRATION_FAIL / BUILD_FAIL / TEST_FAIL / INCONCLUSIVE. Options `--prs n,n`, `--cases id,id`, `--test-scope auto\|module\|full\|none`, `--jdk 8\|11\|17`, `--limit N`, `--records`, `--status`, `--redo`. |
| `repatch report [db]` | Funnel/outcome tables, figures and summary generated from the dataset. |

Details in [The validation study](#the-validation-study-build--test-stages).

### Token

| Command | What it does |
|---|---|
| `repatch token` | Prompt for a GitHub token (input hidden), verify it, save it. |
| `repatch token --status` | Is one configured, and where from? |
| `repatch token --clear` | Forget the saved token. |
| `repatch token <value>` | Non-interactive set. |

## 4. Running your own scenarios

A *scenario* is one pull request, opened against a **source** repository
(the mainline), integrated into a **target** repository (a fork of it).
RePatch and plain `git cherry-pick` both attempt the integration and the
conflicts each produces are recorded.

```
repatch week1 apache/kafka linkedin/kafka 16954
#       ^db    ^source      ^target        ^PR number (on the source repo)
```

- `<db>` is any label (letters, digits, `_`); results land in the MySQL
  database `repatch_<db>`, so `repatch verdicts week1`, `repatch log
  week1`, `repatch results 16954 repatch_week1` all work afterwards.
  Re-using a label starts over (the database is recreated) unless you
  pass `--keep-db`, which resumes and skips scenarios already done.
- Repositories may be written as `owner/repo`, `github.com/owner/repo`
  or a full `https://github.com/owner/repo(.git)` URL.
- `--dry-run` prints the plan — which clones, which mode — and exits.
  Default timeout: 15 min × scenarios, minimum 90 min.
- Any target other than `linkedin/kafka` is cloned at first use (with
  the mainline fetched as a remote) and runs in **paper-parity mode** —
  a minimal single-module IDE model, the same condition the paper gave
  its non-kafka projects. `linkedin/kafka` targets use the fully
  modeled kafka clone. Clones are cached in the data volume.

**Batch runs from a CSV.** Column 1 = PR number, column 2 = source,
column 3 = target; any further columns are ignored, so use them for
your own notes. A header row, blank lines and `#` comment lines are
skipped; duplicates collapse to one scenario.

```csv
pr,source,target,comment
16954,apache/kafka,linkedin/kafka,the acceptance PR
12363,apache/kafka,linkedin/kafka
1187,DSpace/DSpace,ufal/clarin-dspace,non-kafka target -> parity mode
```

```
repatch week1 scenarios.csv --dry-run     # check the plan
repatch week1 scenarios.csv               # run it
```

**Getting a CSV into the container.** The container looks for the file
as given, then under `/home/repatch/import`. Two ways to put it there:

```bash
# (a) the launch commands above already share ./containers/import — just
#     drop files in that folder on the host
#     inside:  repatch week1 scenarios.csv

# (b) copy into a running container
docker cp scenarios.csv repatch:/home/repatch/import/
```

If a file isn't found, the CLI prints both recipes and lists what is
currently in the import folder.

## 5. GitHub token

Every scenario fetches PR metadata from the GitHub API; anonymous access
is limited to 60 requests/hour and stalls any real run. Create a token
at https://github.com/settings/tokens — **no scopes / permissions are
needed** (classic token with every box unchecked, or a fine-grained
token with public-repository read access): the pipeline only reads
public PR metadata, and any token lifts the limit to 5,000 requests/hour.

The first interactive launch (mode (a)) prompts for it; in mode (b) run
`repatch token` once. It is written to `/home/repatch/data/github-token`
(mode 0600) in the **data volume**, so it survives container restarts
and image rebuilds, is never printed, and is never baked into the image.
Non-interactive alternatives: `-e RP_GITHUB_TOKEN=...` on `docker run`,
or `--token` on a run command.

## 6. Browsing the databases

Inside the container: `repatch runs`, `repatch verdicts`, `repatch sql`.

In a browser with **phpMyAdmin** — a second container on the private
docker network (requires the repatch container started with
`-e RP_DB_BIND=0.0.0.0` and `--network repatch-net`, as in launch mode (b)):

```powershell
docker run -d --name repatch-pma --restart unless-stopped --network repatch-net -p 8080:80 `
  -e PMA_HOST=repatch -e PMA_USER=repatch -e PMA_PASSWORD=repatch phpmyadmin
```

Browse http://localhost:8080 — every run database (`repatch_sample`,
`repatch_week1`, …) is listed on the left; it logs in automatically.
No MySQL port touches the host; the two containers talk over the
private network. MySQL is live only while the `repatch` container is
running (phpMyAdmin shows a connection error otherwise; the data is
safe in the volume).

Host tools (MySQL Workbench, DBeaver) instead: add
`-p 127.0.0.1:3307:3306` to the repatch container and connect to
`127.0.0.1:3307`, user `repatch`, password `repatch`.

Take it anywhere: `mysqldump` a run database from inside the container
(`mysqldump -h127.0.0.1 -urepatch -prepatch repatch_week1 > /home/repatch/results/week1.sql`)
and `docker cp repatch:/home/repatch/results/week1.sql .`

## 7. What persists

Everything you care about lives in the two named volumes, not in the
container: `repatch-data` holds every run database, the GitHub token,
the kafka template and all cached clones and dependency caches;
`repatch-results` holds merged result trees, run logs and validation
datasets (Docker Desktop → **Volumes** tab). Containers — including
`--rm` ones — come and go; the volumes stay until you `docker volume rm`
them, and they survive image rebuilds. Each run gets its own database,
so results accumulate and stay browsable across sessions.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `error during connect: ... dockerDesktopLinuxEngine: The system cannot find the file specified` | Docker Desktop isn't running. Start it, wait for "Docker Desktop is running", check `docker version` shows a *Server* section. |
| `docker build` finished but nothing in Docker Desktop | Look under **Images**, not Containers — a container only exists once you `docker run`. |
| `Conflict. The container name "/repatch" is already in use` | A previous container with that name exists (possibly in *Created* state). `docker rm -f repatch`, then rerun. Containers hold no data. |
| phpMyAdmin: "cannot connect to MySQL server" | The `repatch` container isn't up (`docker ps`), or was started without `-e RP_DB_BIND=0.0.0.0` / `--network repatch-net`. On a fresh volume, wait ~20 s for MySQL to initialize. |
| Run stalls on "fetch attempt" / PRs skipped | No GitHub token (60 req/h anonymous). `repatch token --status`, then `repatch token`. |
| Exit code 42 | At least one vacuous inversion — RePatch degraded to a plain cherry-pick for a scenario. Expected for non-kafka (parity-mode) targets; a problem on kafka. See `repatch log`. |
| Killed / exit 137 on long runs | Out of memory. Raise `--memory` (20 g for the full paper run) and Docker Desktop's resource limit. Resume with `--keep-db`. |
| File not found for `repatch <db> file.csv` | Put the file in `containers/import/` on the host (mounted at `/home/repatch/import`) or `docker cp` it there; the error message lists what's in the folder. |
| Windows: `-v ${PWD}\containers\import` fails | Run from the repository root in PowerShell (not cmd), or give the absolute path `C:\path\to\RePatch\containers\import`. Avoid cloning under OneDrive — its sync can lock files Gradle/Docker write. |

### Windows notes

- Clone normally — `.gitattributes` pins the shell scripts to LF so the
  image builds correctly regardless of `core.autocrlf`.
- In Docker Desktop you can start the container from the GUI (it needs
  nothing external), but you won't get a terminal that way; use the
  `docker run -it ...` / `docker exec -it repatch bash` commands from
  PowerShell, or the running container's **Exec** tab.

## The full paper run

`repatch run --dataset complete` runs the paper's **full evaluation:
all 477 distinct scenarios** in `complete_data` (478 lines, one
duplicate).

- **393 kafka mainline→fork scenarios** run with the provisioned clone
  and regenerated module model — the honest engine, same condition as
  every validated baseline.
- **84 remaining scenarios** (DSpace ×54, bitcoinj ×12, reverse-kafka
  ×7, checker-framework ×8, javacc ×2, sqlite-jdbc ×1) run in
  **paper-parity mode**: their repos are cloned at first use with a
  minimal single-module `.idea` — equivalent to what the paper's
  2020-era platform gave them. The refactoring engine may partially
  no-op there; the run reports vacuous-inversion counts instead of
  hiding them. Read those verdicts as paper-condition numbers, not
  honest-engine numbers.
- Checkouts follow the **`<Owner>-<RepoName>` naming spec**
  (`RepoNaming.java`, e.g. `linkedin-kafka`, `apache-kafka`), and
  patches attach to projects by exact fork-URL match — this is what
  lets both directions of the same repository coexist in one run.

Practical notes:

- **Budget ~15–30 hours** (the default timeout for this mode is 48h)
  and **~150 GB free disk**: every conflicting patch exports a merged
  result tree (~0.5–1 GB for kafka-sized repos) into the results
  volume; first use also downloads each non-kafka repo (~3 GB total).
- **Use a GitHub token** (`--token <tok>` or `RP_GITHUB_TOKEN`): each
  patch fetches PR metadata, and anonymous access (60 requests/hour)
  will stall and eventually skip patches.
- **Interrupted? Resume with** `repatch run --dataset complete --keep-db
  --token <tok>` — completed merge scenarios are skipped.
- A non-zero exit of 42 means at least one vacuous-inversion event was
  detected; the verdicts still complete but check the run log.

```powershell
docker run -it --rm -v repatch-data:/home/repatch/data -v repatch-results:/home/repatch/results --memory 10g repatch-headless
repatch run --dataset complete --token ghp_yourtoken
```

## The validation study (build + test stages)

`repatch run` covers level 1 of the RePatch 2.0 validation study (does
RePatch produce a conflict-free target-side change?) and, since the
provenance capture landed, exports the **exact RePatch-produced target
state** per scenario (`repatch-state.bundle` + `repatch-state.json` next
to the evidence trees). `repatch validate` adds levels 2–3 on top of a
completed run:

```
repatch validate                      # all conflict-free cases of the last run
repatch validate --db repatch_complete --prs 16954,12363
repatch validate --test-scope none    # build stage only (fast first pass)
repatch validate --status             # progress
repatch report                        # tables/figures/summary from the dataset
```

For every case whose integration was conflict-free it builds and tests
**both** the baseline target (`target_revision`, before integration) and
the RePatch-produced state, in a dedicated scratch clone (never the
pipeline's clones), with a per-project JDK (8/11/17 are in the image).
Tests already failing at the baseline are not counted as regressions —
only newly failing tests are. Every case ends in exactly one of `VALID |
INTEGRATION_FAIL | BUILD_FAIL | TEST_FAIL | INCONCLUSIVE`, with stage-wise
detail preserved. Results: one JSON record per case in
`results/validation/<db>/dataset.jsonl` (schema: `validation/SCHEMA.md`
in the repo), raw build/test logs beside it, report artifacts from
`repatch report`. Baseline results are cached per project+revision (every
case of a project shares its baseline), so the expensive side runs once.
Gradle multi-module projects (kafka) default to **module-scoped tests**
(only modules the change touches, applied identically to baseline and
post state); use `--test-scope full` for the entire suite. Do not run
`validate` while a pipeline run is in progress. Interrupt/resume is safe:
the dataset is checkpointed after every case, and already-classified
cases are skipped (`--redo` to force).

The first `repatch run` provisions the kafka evaluation clone into the
data volume (one-time ~500 MB download + dependency resolution); every
later run starts from that cache. Each run gets its own database
(`repatch_pr16954`, `repatch_sample`, ... — override with `--db NAME`,
resume one with `--keep-db`), so results accumulate and stay browsable
across sessions: databases, clone, and caches all live in the
`repatch-data` volume, merged result trees and logs in
`repatch-results`. Type `exit` to leave; MySQL shuts down cleanly.

## What the image bakes in (and why)

- **IntelliJ 2024.3.7 + Gradle + all dependencies** — downloaded at build
  time so runs don't start with a multi-gigabyte fetch.
- **MySQL 8 server** — runs inside the container as the `repatch` user;
  the data directory is initialized on first boot **in the data volume**,
  so databases persist across containers and image rebuilds.
- **RefactoringMiner 2.1.0** as a flat local-Maven artifact (the build
  resolves it with `transitive = false`; Central's POM would drag six
  transitives onto the classpath).
- **JDK 11 alongside JDK 17**: the evaluation project's model pins project
  SDK "11" (kafka-era); IntelliJ auto-registers the baked JDK from
  `/usr/lib/jvm`, and kafka's own gradle (used at provisioning) predates
  JDK 17 support.
- **A git identity** — the pipeline creates commits (undo commits); without
  `user.name`/`user.email`, `git commit` silently refuses and every
  inversion degrades to a no-op.
- **The `.idea` project metadata** for the kafka clone
  (`assets/kafka-model-overlay.tar.gz`: `modules.xml` naming the 43
  modules, `misc.xml` pinning SDK 11). The `*.iml` module files themselves
  are REGENERATED at provisioning time with kafka's own
  `gradle idea` (init script `allprojects { apply plugin: 'idea' }`) so
  their library jar paths point at this container's dependency cache —
  baked imls from another machine reference jars that don't exist here,
  and without resolvable libraries every inversion silently no-ops.

## Determinism

`repatch run` reproduces the run hygiene the digit-identical acceptance
runs were validated with: the IDE sandbox `system` directories are wiped
before each run (cold VFS/workspace caches), the working clone is
recreated from the pristine template per run, and the pipeline itself
pins the project model for the process lifetime. A run that logs any
vacuous inversion (the engine silently no-oping down to a plain
cherry-pick) exits 42 with a loud warning instead of passing as success.
Memory floor: ~8 GB.

## Layout

```
containers/
├── Dockerfile            # eclipse-temurin:17-jdk-noble + MySQL + baked caches
├── docker-compose.yml    # optional single-service convenience wrapper
├── entrypoint.sh         # boots MySQL, then shell / command / CI run
├── bin/
│   ├── repatch           # the in-container CLI
│   └── repatch-lib.sh    # shared run/provisioning/verdict logic
└── assets/
    ├── kafka-model-overlay.tar.gz
    ├── refactoring-miner-2.1.0.jar
    └── refactoring-miner-2.1.0.pom
```
