# Side-by-side evaluation: RePatch 1.x (IntelliJ 2020.1.2) vs 2.0 (IntelliJ 2024.3.7)

## What we are doing

Testing whether the 1.0→2.0 IntelliJ migration preserves merge verdicts, by running
BOTH engines over the same 393-PR kafka patch list from the **same left commit**
(`linkedin/kafka @ 31df5cec3298e31b55888307929904df83cc8753`, branch `3.0-li`) and
diffing the per-PR verdict triples (files/conflicts/loc).

Already validated on the 6-PR sample (2026-07-02, same-SHA A/B): **byte-identical
verdicts on all six**, including 12660 = 0/0/0 WIN for both and 12289 = 1/5/57 TIE for
both with the same residual file (`StoreQueryIntegrationTest.java`) — i.e. 12289 is a
shared RefactoringMiner detection-window limit (Mode B), not a migration regression.
This run extends the comparison to the full kafka dataset.

## The two branches

- `eval/replicate-1x-workflow` — **2.0 unified**: merge of `diag/residual-conflict-file`
  (source-root provisioning, ChangeSig NPE fix) + `migration/phase7-12660` (12660 fixes,
  comment-only-conflict filter, pipeline hardening), plus `EdtSafe` (git4idea off-EDT,
  Windows-only path, no-op on Linux) and this 393-row patch list. JDK 17, Gradle 9.
- `eval/baseline-2020-12289` — **1.x baseline** (`main`) + harness-only fixes so it runs
  outside the original container: async Git-root registration wait, `inheritIO` backport
  of 87bd81c (latent `waitFor` pipe deadlock in `runSystemCommand`), and the same
  393-row patch list. Engine code untouched (verified: zero diff on the merge pipeline).
  JDK 8/11, Gradle 6.8.

## How to run (each branch)

1. GitHub token (no scopes needed — public read only, just lifts the rate limit):
   `echo OAuthToken=<TOKEN> > src/main/resources/github-oauth.properties`
   (gitignored; the repo-root `github-autho.properties` is NOT read.)
2. MySQL reachable as root/root. Default URL is `jdbc:mysql://localhost/refactoring_aware_integration_repatch`;
   override per run with env `JDBC_URL` / `JDBC_URL_WITHOUT_DATABASE` / `JDBC_USER` / `JDBC_PASSWORD`
   (both branches honor these). To run the two engines in parallel, point them at
   different MySQL instances/ports; sequentially, drop the DB between runs:
   `DROP DATABASE IF EXISTS refactoring_aware_integration_repatch;`
3. Before each run: reset the kafka checkout to the pin and clear results —
   `git -C <dataPath>/<checkout> reset --hard 31df5cec3298...  && rm -rf ~/results`
   (2.0's checkout dir is `linkedin-kafka`, baseline's is `kafka`; both auto-clone if missing).
4. Launch:
   `./gradlew runIde -Pmode=integration -PdataPath=repatch-integration-projects -PevaluationProject=kafka`
   (dataPath is resolved relative to $HOME. On the 2.0 branch the bundled reset script
   runs automatically; add `-x resetIntegrationFixtures` if bash/mysql-cli aren't available.)
5. Troubleshooting from the Windows validation runs (may not apply on Linux):
   - If the IDE wedges at project open: disable the Gradle plugin in the sandbox —
     `echo -e 'org.jetbrains.plugins.gradle\ncom.intellij.gradle' > build/idea-sandbox/config/disabled_plugins.txt`
     (2020 sandbox path may differ; the kafka Gradle sync is unnecessary — 2.0 provisions
     source roots itself, 1.x registers them reactively per-op).
   - Baseline `Repository for Integration -> null`: seed `<checkout>/.idea/vcs.xml` with a
     Git mapping (the branch already waits/retries, but the mapping makes it instant).

## Extracting the comparison

After each run, dump verdicts:

```sql
SELECT p.number AS pr, mr.merge_tool,
       CONCAT(mr.total_conflicting_files,'/',mr.total_conflicts,'/',mr.total_conflicting_loc) AS verdict
FROM merge_result mr JOIN patch p ON mr.patch_id = p.id
ORDER BY p.number, mr.merge_tool;
```

Export each run's result set (e.g. `mysql ... -B -e "<query>" > verdicts-<engine>.tsv`) and
diff the RePatch rows PR-by-PR. Expected outcome if the migration is verdict-preserving:
identical triples everywhere; the Git-CherryPick rows should match trivially (same git,
same left SHA) and serve as a sanity check that the two runs evaluated the same scenarios.
Baseline caveat: 1.x has no per-PR error isolation — if it dies mid-run (e.g. one
unresolvable PR), note where, restart is all-or-nothing (drop DB first).
