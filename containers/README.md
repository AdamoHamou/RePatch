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
running locally. From there:

```
repatch run                    # golden 5-patch sample set
repatch run 16954              # one kafka PR (or several: 12363 15889)
repatch run --dataset complete # the full scenario list
repatch run --golden-check     # sample set + diff vs the golden baseline
repatch runs                   # list past run databases
repatch verdicts [db]          # verdict table of a run (default: latest)
repatch log [db]               # page through a run's pipeline log
repatch results [PR]           # locate the merged result trees
repatch sql [db]               # open a mysql shell on the run data
repatch status                 # provisioning / health check
```

The first `repatch run` provisions the kafka evaluation clone into the
data volume (one-time ~500 MB download + dependency resolution); every
later run starts from that cache. Each run gets its own database
(`repatch_pr16954`, `repatch_sample`, ... — override with `--db NAME`,
resume one with `--keep-db`), so results accumulate and stay browsable
across sessions: databases, clone, and caches all live in the
`repatch-data` volume, merged result trees and logs in
`repatch-results`. Type `exit` to leave; MySQL shuts down cleanly.

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
