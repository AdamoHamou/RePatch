# RePatch 2.0 — containerized evaluation

Headless, reproducible runs of the RePatch integration pipeline
(IntelliJ 2024.3.7 / JDK 17 / MySQL 8), mirroring how the original paper
artifact shipped as a container.

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
- **The 43-module JPS project model** for the kafka clone
  (`assets/kafka-model-overlay.tar.gz`: `.idea/` + 43 `*.iml`). The
  evaluation clone must have a real project model or every PSI lookup
  silently fails; the overlay is applied to the template at provisioning
  time and also serves as `-Drepatch.modelBackupDir` (the model self-heal
  backstop). To regenerate it from scratch: run `gradle idea` on the kafka
  clone with an init script containing `allprojects { apply plugin: 'idea' }`,
  then tar `.idea` and all `*.iml` files.

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
