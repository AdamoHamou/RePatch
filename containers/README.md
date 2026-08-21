# RePatch 2.0 — containerized evaluation

One self-contained image: the RePatch pipeline (IntelliJ 2024.3.7 /
JDK 17), MySQL 8, and an interactive `repatch` CLI — running headlessly
on Ubuntu 24.04, so it works the same on virtually any machine with
Docker. No sidecar containers, no compose networking: start it, get a
shell, run evaluations.

## Requirements

- Docker (Linux) or Docker Desktop (Windows/macOS; WSL2 backend on
  Windows).
- ~20 GB free disk (5.5 GB image + build layers + kafka clone,
  dependency caches, and MySQL data in a volume) and ~10 GB RAM
  available to Docker (on Windows, set this in Docker Desktop →
  Settings → Resources, or `.wslconfig`).

## Quick start

```bash
# from the repository root
docker build -f containers/Dockerfile -t repatch-headless .

docker run -it --rm \
  -v repatch-data:/home/repatch/data \
  -v repatch-results:/home/repatch/results \
  --memory 10g --shm-size 1g \
  repatch-headless
```

That drops you into a shell inside the container with MySQL already
running locally. **On first launch it asks for a GitHub token** (input
hidden, stored only in the `repatch-data` volume — see below). From
there:

```
repatch run                               # golden 5-patch sample (smoke test)
repatch run --golden-check                # sample + diff vs the golden baseline
repatch run --dataset complete            # the paper's full evaluation (477)

repatch <db> <source> <target> <pr>       # ONE scenario of your choosing
repatch <db> <file.csv>                   # every scenario listed in a CSV

repatch token                             # set / verify the GitHub token
repatch runs                              # list past run databases
repatch verdicts [db]                     # verdict table of a run (default: latest)
repatch log [db]                          # page through a run's pipeline log
repatch results [PR] [db]                 # locate the merged result trees
repatch sql [db]                          # open a mysql shell on the run data
repatch status                            # provisioning / health check
```

### Running your own scenarios

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
- Options: `--dry-run` (print the plan — which clones, which mode — and
  exit), `--keep-db`, `--timeout SECONDS` (default 15 min × scenarios,
  minimum 90 min), `--token TOKEN` (one-off override).
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
# (a) share a host directory at start-up — every file in it is visible
docker run -it --rm -v /path/to/my/csvs:/home/repatch/import \
  -v repatch-data:/home/repatch/data -v repatch-results:/home/repatch/results \
  --memory 10g --shm-size 1g repatch-headless
#     inside:  repatch week1 scenarios.csv

# (b) copy into a running container (find its name with `docker ps`)
docker cp scenarios.csv <container-name>:/home/repatch/import/
```

On Windows PowerShell use `${PWD}` for the current directory, e.g.
`-v ${PWD}\csvs:/home/repatch/import`. With compose, drop files into
`containers/import/` — it is mounted automatically.

### GitHub token

Every scenario fetches PR metadata from the GitHub API; anonymous access
is limited to 60 requests/hour and stalls any real run. The first
interactive launch prompts for a token (create one at
https://github.com/settings/tokens — no scopes are needed for public
repositories). It is written to `/home/repatch/data/github-token`
(mode 0600) in the **data volume**, so it survives container restarts
and image rebuilds, is never printed, and is never baked into the image.

```
repatch token             # prompt (input hidden), verified against api.github.com
repatch token --status    # is one configured?
repatch token --clear     # forget it
```

Non-interactive alternatives: `-e RP_GITHUB_TOKEN=...` on `docker run`,
or `--token` on a run command.

### What persists

Everything you care about lives in the two named volumes, not in the
container: `repatch-data` holds every run database, the GitHub token,
the kafka template and all cached clones and dependency caches;
`repatch-results` holds merged result trees, run logs and validation
datasets. `docker run --rm` containers come and go; the volumes stay
until you `docker volume rm` them. Only one repatch container can hold
the data volume at a time.

### The full paper run

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

### The validation study (build + test stages)

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

### Browsing the databases with phpMyAdmin

By default MySQL listens on the container's loopback only. Set
`RP_DB_BIND=0.0.0.0` to let it accept connections from other containers
(or, combined with `-p`, from the host). The `repatch`/`repatch` account
already permits remote login.

```bash
docker network create repatch-net   # one-time

docker run -it --rm --network repatch-net --name repatch \
  -e RP_DB_BIND=0.0.0.0 \
  -v repatch-data:/home/repatch/data -v repatch-results:/home/repatch/results \
  --memory 10g --shm-size 1g repatch-headless

docker run -d --name repatch-pma --network repatch-net -p 8080:80 \
  -e PMA_HOST=repatch -e PMA_USER=repatch -e PMA_PASSWORD=repatch phpmyadmin
```

Browse http://localhost:8080 — every run database (`repatch_477`,
`repatch_sample`, ...) is visible. No MySQL port ever touches the host;
the two containers talk over the private docker network. To use host
tools (MySQL Workbench, a host phpMyAdmin) instead, publish the port:
`-p 127.0.0.1:3307:3306 -e RP_DB_BIND=0.0.0.0` and connect to
`127.0.0.1:3307`. Remember only one repatch container can hold the
MySQL datadir at a time — browse from the same container that runs the
evaluation, not a second one.

### Compose (optional convenience)

```bash
cd containers
docker compose build
docker compose run --rm headless                     # interactive shell
docker compose run --rm headless repatch run 16954   # one-shot command
```

### Non-interactive / CI

Any arguments after the image name are executed as-is (MySQL is started
first), and running with **no TTY and no arguments** performs one
default sample run — so the old one-shot workflow still works:

```bash
docker run --rm -v repatch-data:/home/repatch/data repatch-headless \
  repatch run --golden-check          # exit code ≠ 0 on baseline mismatch
```

### Windows notes

- Clone normally — `.gitattributes` pins the shell scripts to LF so the
  image builds correctly regardless of `core.autocrlf`.
- In Docker Desktop you can start the container from the GUI (it needs
  nothing external anymore), but you won't get a terminal that way; use
  the `docker run -it ...` command above from PowerShell, or open the
  running container's **Exec** tab and type `bash`.

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
