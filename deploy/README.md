# RePatch Pipeline — Quick Reference

This README sits next to the **RePatch (IntelliJ)** desktop launcher. The
launcher opens IntelliJ IDEA CE 2024.3.7 directly on the RePatch project;
the shared run configuration **"Integration Pipeline (kafka)"** (from the
repo's `.run/` folder) appears in the run-config dropdown — press ▶ to run
the full 5-PR evaluation. Fixtures (database, kafka checkout, sandbox lock)
are reset automatically before every run, and the kafka checkout is **cloned
automatically on first run** — no manual cloning is needed on a fresh machine.

## Accessing the SQL database

The pipeline records merge results in a local MySQL 8 instance.

| Setting  | Value |
|----------|-------|
| Host     | `127.0.0.1` |
| Port     | `3306` |
| User     | `repatch` |
| Password | `repatch` |
| Schema   | `refactoring_aware_integration_repatch` (auto-created on first run) |

Connect from a terminal:

```sh
MYSQL_PWD=repatch mysql -h 127.0.0.1 -u repatch -D refactoring_aware_integration_repatch
```

### The query you almost always want — per-PR verdicts

```sql
SELECT p.number AS pr, mr.merge_tool, mr.total_conflicting_files AS files,
       mr.total_conflicts AS conflicts, mr.total_conflicting_loc AS loc
FROM merge_result mr JOIN patch p ON mr.patch_id = p.id
ORDER BY p.number, mr.merge_tool;
```

Expected verdicts on the current migration branch (lower is better;
RePatch should never be worse than Git-CherryPick):

| PR    | RePatch | Git-CherryPick |
|-------|---------|----------------|
| 13050 | 1/1/6   | 1/1/6 (tie)    |
| 12660 | 2/2/17  | 2/2/17 (open work item: baseline RePatch won 0/0/0) |
| 13023 | 1/1/107 | 1/1/107 (tie)  |

### Other useful tables

- `merge_result` — one row per (PR, merge tool) with conflict totals
- `patch` — the evaluated PRs
- `conflicting_file` / `conflict_block` — per-file and per-block conflict detail
- `refactoring` — refactorings detected by RefactoringMiner per merge scenario

**Note:** every pipeline run **drops and recreates** the schema (via
`scripts/reset-integration-fixtures.sh`). Export anything you want to keep
before re-running:

```sh
MYSQL_PWD=repatch mysqldump -h 127.0.0.1 -u repatch \
  refactoring_aware_integration_repatch > repatch-results-$(date +%F).sql
```

## Running the pipeline without the IDE

```sh
cd <REPATCH_DIR>
JAVA_HOME=<JDK17_DIR> JDBC_USER=repatch JDBC_PASSWORD=repatch \
./gradlew --no-daemon runIde -Pmode=integration \
  -PdataPath=/repatch-integration-projects -PevaluationProject=kafka
```

(`dataPath` is resolved relative to `$HOME`. The kafka fixture lives at
`~/repatch-integration-projects/kafka-linkedin` — the directory is named
`<RepoName>-<Owner>` from the clone URL, so the linkedin/kafka fork can't be
confused with mainline apache/kafka. If it is missing, the pipeline clones
it on first run, pins it to the branch + SHA listed in
`src/main/resources/sample_data/repatch_integration_projects`, and adds
apache/kafka as a second remote named `kafka`. `evaluationProject` selects
which line of that file to run — it is a name filter, not a directory name.)

## Logs

- Run log: IntelliJ run console, or redirect the CLI command to a file
- Pipeline log (appended across runs): `~/temp/logs/kafka-linkedin`
  (named after the checkout directory; was `~/temp/logs/kafka` before the
  owner-suffixed naming)
- Classified per-operation failures: grep the run log for `[RefactoringExecution]`
- Health checks: `grep -c IndexNotReadyException <log>` should be **0**
