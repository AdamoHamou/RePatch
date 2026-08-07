# RePatch 2.0 — containerized evaluation

Headless, reproducible runs of the RePatch integration pipeline
(IntelliJ 2024.3.7 / JDK 17 / MySQL 8), mirroring how the original paper
artifact shipped as a container.

## Requirements

- Docker (Linux) or Docker Desktop (Windows/macOS; WSL2 backend on Windows).
- ~20 GB free disk (5.4 GB image + build layers + kafka clone and
  dependency caches in volumes) and ~10 GB RAM available to Docker
  (on Windows, set this in Docker Desktop → Settings → Resources, or
  `.wslconfig`).

## Quick start

```bash
cd containers
docker compose build                 # one-time: bakes the IDE + all deps (~10 min, large image)
docker compose run --rm headless     # golden 5-patch sample run end-to-end
```

The first run additionally provisions the kafka evaluation clone into a
named volume (~500 MB download); every later run starts from that cache.
Verdicts are printed at the end of each run and persist in the MySQL
volume; merged result trees land in the `results` volume.

### Windows notes

- Clone normally — `.gitattributes` pins the shell scripts to LF so the
  image builds correctly regardless of `core.autocrlf`.
- The `VAR=value docker compose run ...` syntax above is bash-only. Use
  the portable `-e` form instead, which works in PowerShell and cmd too:

```powershell
docker compose run --rm -e RP_PRS=16954 headless
docker compose run --rm -e RP_GOLDEN_CHECK=1 headless
```

## Run modes

| Command | What it does |
|---|---|
| `docker compose run --rm headless` | the committed 5-patch sample set |
| `RP_GOLDEN_CHECK=1 docker compose run --rm headless` | sample set + diff against the committed golden baseline (exit ≠ 0 on mismatch) |
| `RP_PRS=16954 docker compose run --rm headless` | one specific kafka PR |
| `RP_PRS=12363,15889 docker compose run --rm headless` | several PRs, sequentially |
| `RP_DATASET=complete docker compose run --rm headless` | the full complete_data scenario list |

Other knobs (see `entrypoint.sh` header for the full contract): `RP_DB`
(per-run schema name), `RP_KEEP_DB=1` (resume instead of recreate),
`RP_TIMEOUT` (seconds), `RP_FORK_URL` / `RP_MAINLINE_URL` (evaluation
repo override).

A GitHub token is optional (anonymous API access suffices for the sample
runs). To use one, bind-mount a real properties file over the committed
template:

```yaml
    volumes:
      - ./github-oauth.properties:/home/repatch/RePatch/src/main/resources/github-oauth.properties:ro
```

## What the image bakes in (and why)

- **IntelliJ 2024.3.7 + Gradle + all dependencies** — downloaded at build
  time so runs don't start with a multi-gigabyte fetch.
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

The entrypoint reproduces the run hygiene the digit-identical acceptance
runs were validated with: the IDE sandbox `system` directories are wiped
before each run (cold VFS/workspace caches), the working clone is
recreated from the pristine template per run, and the pipeline itself
pins the project model for the process lifetime. Memory floor: ~8 GB
(the compose file sets `mem_limit: 10g`).

## Layout

```
containers/
├── Dockerfile            # eclipse-temurin:17-jdk-noble + baked caches
├── docker-compose.yml    # mysql:8 sidecar + headless service + volumes
├── entrypoint.sh         # provisioning, hygiene, launch, verdict report
├── mysql-init/10-grants.sql
└── assets/
    ├── kafka-model-overlay.tar.gz
    ├── refactoring-miner-2.1.0.jar
    └── refactoring-miner-2.1.0.pom
```
