# RePatch 2020.1.2 → IntelliJ 2024.3 Migration — Technical Report

Status date: 2026-06-05. Branch: `migration/intellij-2024-platform-facade`.
Author: migration onboarding (five-week plan, `plan.tex`); this report is the
Week 5 hand-off deliverable.

## 1. What was migrated

### Build (Week 1)

| Component | Before | After |
|---|---|---|
| Gradle | 6.8 | 9.0.0 |
| IntelliJ plugin | gradle-intellij-plugin 0.x (`intellij {}`) | IntelliJ Platform Gradle Plugin 2.16.0 (`intellijPlatform {}`) |
| Target IDE | IntelliJ CE 2020.1.2 | IntelliJ CE 2024.3.7 (`sinceBuild 243`) |
| JDK | 8/11 era | 17 (Temurin `jdk-17.0.19+10`) |
| Test frameworks | implicit | explicit `TestFrameworkType.Platform` + `Plugin.Java` |

### Runtime / entry point (Week 2)

`IntegrationPipeline` remains an `ApplicationStarter` (command
`integration`), now launched via `runIde -Pmode=integration` with fixture
auto-reset wired into the Gradle task graph. The JVM exits via
`Runtime.halt(0)` after explicit stream flush — a non-daemon pool thread
otherwise keeps the IDE process alive after the work is done.

### PSI compatibility + adapter layer (Week 3)

All IntelliJ runtime services are accessed through one seam:
`edu.unlv.cs.evol.repatch.platform.PlatformFacade`
(+ `IntelliJ2024PlatformFacade`), ~13 methods: openProject,
waitForSmartMode, refreshAllVfs, commitAndReparse, runWriteCommand,
runReadAction, runWriteAction, runInSmartReadAction, invokeAndWait,
saveAllDocuments, closeActiveUsageView, isUnitTestMode,
getSelectedTextEditor. Services built on it: `VfsSyncService`,
`PsiSearchService`, `IndexingService`, `ProjectRootsService`.

### Refactoring execution stabilization (Week 4)

A shared execution contract
(`docs/refactoring-execution-contract.md`): `RefactoringOperation`
(prepare / execute / verifyPostcondition), `RefactoringExecutionService`
(six-step driver), `RefactoringExecutionResult` (SUCCESS /
PRECONDITION_FAILED / PROCESSOR_THREW / POSTCONDITION_BROKEN /
UNSUPPORTED_SHAPE). Both dispatchers (`InvertRefactorings`,
`ReplayRefactorings`) route every operation through it; every failure
logs one `[RefactoringExecution]` line. Four operation classes are fully
migrated (`InvertMoveRenameClass`, `InvertMoveRenameMethod`,
`ReplayMoveRenameMethod`, `InvertExtractMethod`); the other 19 run
through `RefactoringOperation.legacy` (classified, internals unchanged).

### Pipeline operability (Week 5)

- **Auto-clone**: evaluation repos clone themselves on first run into
  owner-suffixed checkouts (`<RepoName>-<Owner>`, e.g. `kafka-linkedin`),
  pinned to the branch + SHA in
  `sample_data/repatch_integration_projects` (single source of truth,
  also parsed by the reset script). Zero manual setup on a fresh machine.
- **Failure classification**: git commit/cherry-pick failures are
  `[GitPipeline]` classified errors; a no-op commit ("nothing to commit")
  deliberately resolves HEAD (`COMMIT_NOOP`) — see §3, lessons. One
  failing PR no longer aborts the remaining PRs
  (`[Pipeline] SCENARIO_FAILED`, per-scenario isolation).
- **Preflight**: git identity + MySQL reachability checked at startup,
  all failures reported together with exact fixes.
- **Run summary**: `PipelineRunResult` prints one `[PipelineSummary]`
  line per PR (outcome, elapsed, verdicts) plus totals at end of run.

## 2. Verified results

Floor verdicts (must not regress; RePatch vs Git-CherryPick,
files/conflicts/LOC), verified locally and on the work server:

| PR | RePatch | Git-CherryPick | Note |
|---|---|---|---|
| 12592 | n/a | n/a | non-conflicting |
| 13032 | n/a | n/a | non-conflicting |
| 13050 | 1/1/6 | 1/1/6 | tie |
| 12660 | 2/2/17 | 2/2/17 | **regression vs 2020 baseline 0/0/0 — open item** |
| 13023 | 1/1/107 | 1/1/107 | tie |

Stability metrics vs the start of Week 4: `IndexNotReadyException`
1310/run → **0**; un-inverted refactorings 120 (silent) → 51
(classified); un-replayed 98 (silent) → 29 (classified). Test suite:
NO-SOURCE → 13 tests green. Deployed on a fresh Ubuntu 24.04 server
(`/opt/repatch`) with 5/5 PRs green.

## 3. Hard-won platform lessons

1. **The pipeline main runs ON the EDT** (`ApplicationStarter`).
   `runWhenSmart` + `Future.get()` deadlocks;
   `DumbService.runReadActionInSmartMode` silently degrades on the EDT.
   The working primitive is event-pumping (`IdeEventQueue.flushQueue` +
   `completeJustSubmittedTasks` in a loop) behind
   `PlatformFacade.waitForSmartMode` / `runInSmartReadAction`.
2. **Smart mode ≠ current indexes** on 2024.x. After checkouts,
   `FilenameIndex`/`findClass` can miss on-disk files. Reliable
   resolution is index-independent: `LocalFileSystem.findFileByPath` →
   `PsiManager.findFile` (see `PsiSearchService`).
3. **Refactoring processors take their own write actions** — invoke via
   `invokeAndWait`, never inside an outer write command.
4. **Correct-by-accident code paths**: `DoGitCommit` used to "parse" the
   sha out of git's *failure* output when there was nothing to commit
   (squash-merge PRs have zero right-side refactorings), accidentally
   returning the right HEAD — exactly what cherry-pick needed. A
   too-strict guard regressed all verdicts to -1. Any change to the
   commit/cherry-pick/checkout path needs a full 5-PR run with verdict
   comparison before it lands; exit codes and unit tests don't cover it.
5. **Plugin-classloader JDBC**: `DriverManager`'s SPI lookup fails under
   the plugin classloader; load the driver class explicitly
   (`Class.forName(...)`) as ActiveJDBC does.

## 4. Implementation-only APIs

Removed from the production path (Week 3): `DumbServiceImpl`,
`JavaPsiFacadeImpl`. Retained and documented (the IntelliJ Plugin
Verifier reports these four; they are non-gating in CI):

- `com.intellij.ide.impl.ProjectUtil.openOrImport`
  (`IntelliJ2024PlatformFacade`) — 2024.x has no public-API
  open-or-import for a bare directory (the fixture deletes `.idea`
  every run).
- `com.intellij.ide.IdeEventQueue.flushQueue`
  (`IntelliJ2024PlatformFacade`) — required by the EDT event pump
  (lesson 1).
- `com.intellij.openapi.module.Module.getModuleFile` /
  `getModuleFilePath` (`ProjectRootsService`) — RefMerge-era module
  lookup; replace with public Module APIs when ProjectRootsService is
  revisited for the 12660 content-registration work.

The verifier gate itself: `COMPATIBILITY_PROBLEMS` + `INVALID_PLUGIN`
fail the build; the single ignored problem (ActiveJDBC
instrumentation-generated `convertWith` referencing a protected method —
generated bytecode, never invoked) is listed in
`.github/plugin-verifier-ignored-problems.txt`.

## 5. Known gaps and recommended next steps

Ordered by expected value:

1. **PR 12660 verdict (2/2/17 vs baseline 0/0/0)** — remaining ~51
   inversion failures are classified into three buckets: (a) project
   content registration — `openOrImport` on the bare checkout yields a
   "non-default and non-directory based project", blocking writes via
   `NonProjectFileWritingAccessProvider` (~4/run) and causing index
   misses; fixing registration is the highest-value lever; (b)
   cross-refactoring ordering (method inversions inside renamed
   classes); (c) three concrete PSI-shape bugs (`InvertInlineMethod`
   range IAE, `MoveMembersProcessor` null target,
   `PsiSearchService.parameterComparator` substring math on
   RENAME_PARAMETER).
2. **Migrate the remaining 19 operation classes** onto the 4A contract
   (pattern: `InvertMoveRenameMethodTest` + `InMemoryPlatformFacade`),
   converting silent-return paths into classified results.
3. **~50% fewer recorded refactorings (1362 vs 2724)** — JGit-version
   interaction in the left-side detect; verdicts unaffected. Options:
   pin JGit 5.x, upgrade RefactoringMiner past 2.1.0, or accept.
4. **Deeper Phase 4 modernization** (deferred by plan): module split,
   persistence isolation, `LoggingService` carve-out from `Utils`,
   configurable timeout policy objects, precise unsupported-refactoring
   classification.
5. **Gradle auto-import noise**: disable external-system auto-import for
   the evaluation project (cosmetic, saves cycles).

## 6. Reproducibility

- Build: `JAVA_HOME=<jdk17> ./gradlew --no-daemon build -x test`
- Tests: `./gradlew --no-daemon test`
- Pipeline: `runIde -Pmode=integration -PdataPath=/repatch-integration-projects
  -PevaluationProject=kafka` (auto-resets fixtures, auto-clones on first
  run, ~6–8 min) — or the shipped run config
  `.run/Integration Pipeline (kafka).run.xml`.
- Verification: `[PipelineSummary]` lines at end of run; SQL recipe and
  expected verdicts in `deploy/README.md`.
- CI: `.github/workflows/gradle.yml` — build, tests, and IntelliJ Plugin
  Verifier (`verifyPlugin`) as a mandatory gate.
