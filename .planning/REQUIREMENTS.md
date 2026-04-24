# RePatch 2.0 Modernization — Requirements

**Last updated:** 2026-04-23
**Spec source:** `RePatch_2_0.pdf` (5 tracks, 27 atomic WBS tasks)
**Delivery model:** One Git branch per WBS task, each branch must compile + build cleanly.

## v1 Requirements

Each requirement corresponds to exactly one WBS task ID and one phase. REQ-IDs reuse the WBS task ID verbatim.

### Track P0 — Baseline Freeze

| REQ-ID  | Requirement |
|---------|-------------|
| P0-01   | Create `repatch-1.x-baseline` git tag and snapshot working state of the pre-modernization codebase. |
| P0-02   | Produce an environment manifest documenting JDK, Gradle, IntelliJ version, MySQL config, and GitHub auth requirements. |
| P0-03   | Code hotspot inventory — grep all `com.intellij.*`, `git4idea.*`, and `*.impl.*` imports and produce a migration spreadsheet grouped by track. |
| P0-04   | Regression scenario list captured from sample data — enumerate known-good end-to-end scenarios that must still pass after modernization. |

### Track A — Build & CI

| REQ-ID | Requirement |
|--------|-------------|
| A1 | Upgrade Gradle wrapper to 8.10, switch to JDK 17 toolchain, migrate `compile`→`implementation`, remove `apply plugin: 'maven'`. |
| A2 | Replace `org.jetbrains.intellij` 0.7.3 with `org.jetbrains.intellij.platform` 2.x; add `intellijPlatform { defaultRepositories() }`; move dependencies into `intellijPlatform {}` block; target `IC-2024.2.4`. |
| A3 | Clean up any remaining deprecated dependency configurations; verify `runtimeOnly`, `testRuntimeOnly`, etc. |
| A4 | Reconfigure plugin metadata (`pluginConfiguration {}`, `sinceBuild=242`, `untilBuild` empty); verify `appStarter` extension registration; update `plugin.xml` `<depends>` entries. |
| A5 | Add `verifyPlugin` task; wire into GitHub Actions CI with artifact upload of verifier report; snapshot first verifier report as Track C intake. |
| A6 | Document local build and run workflow in README; update run instructions for `./gradlew runIde` and `./gradlew runIntegrationPipeline`. |

### Track B — Startup & Runtime Entry

| REQ-ID | Requirement |
|--------|-------------|
| B1 | Audit action-based vs. headless execution requirements; update `ApplicationStarter` to modern signature (`main(List<String>)`, `isHeadless()=true`, `getRequiredModality()=NOT_IN_EDT`). |
| B2 | Replace positional argument parsing in `IntegrationPipeline` with typed `EvaluationConfig` record; layered resolution (CLI > env vars > defaults); validation with friendly errors. |
| B3 | Encapsulate project open/close lifecycle — implement `ProjectOpenWaiter` that waits for indexing + VCS mapping + VFS settled; wrap all `openProject` calls. |
| B4 | Add robust exit codes (0=success, 1=runtime error, 2=config validation failure) and startup validation; introduce `ExitHandler` port so tests can capture exits. |

### Track C — Platform Compatibility Layer

| REQ-ID | Requirement |
|--------|-------------|
| C1 | Remove all `DumbServiceImpl` usage; replace with `DumbService.getInstance(project)` and modern APIs (`waitForSmartMode`, `runWhenSmart`, `runReadActionInSmartMode`). |
| C2 | Remove all `JavaPsiFacadeImpl` usage; replace with `JavaPsiFacade.getInstance(project)` and proper scope usage. |
| C3 | Isolate project-opening APIs behind `PlatformProjectService` adapter; remove direct `ProjectUtil` impl-class calls; handle `openOrImportAsync` Java interop. |
| C4 | Centralize VFS/PSI synchronization — implement correct ordering (saveAllDocuments → git op → VfsUtil.markDirtyAndRefresh → commitAllDocuments → waitForSmartMode); remove `VirtualFileManager.syncRefresh()` calls. |
| C5 | Standardize read/write action execution — wrap all PSI reads in `ReadAction.compute`; all PSI mutations through `WriteCommandAction.runWriteCommandAction`; marshal from headless threads to EDT correctly. |
| C6 | Refactor source-root and module manipulation utilities; remove remaining `*.impl.*` class imports; fix `ServiceManager.getService` calls (removed in 2023.1). |

### Track D — Refactoring Execution Modernization

| REQ-ID | Requirement |
|--------|-------------|
| D1 | Define shared execution contract (interface + standard protocol) for all replay operations — precondition validation, PSI target resolution, execution in write context, post-op sync, typed `ReplayResult`. |
| D2 | Define shared execution contract for all invert operations — symmetric to D1; typed `InvertResult`. |
| D3 | Add structured result types throughout — `ReplayResult`, `InvertResult`, `EvaluationResult`, `ConflictDetectionResult`, `ProjectPreparationResult`; replace null returns and `e.printStackTrace()`. |
| D4 | Validate refactoring processor preconditions for every refactoring type RePatch supports — constructor arity changes for `RenameProcessor`, `MoveClassesOrPackagesProcessor`, `ExtractMethodProcessor`, etc. |
| D5 | Add per-operation regression tests — one test per refactoring type (replay + invert); use `LightJavaCodeInsightFixtureTestCase`; use before/after fixture files from `src/test/resources/`. |

### Track E — Functional Improvements

| REQ-ID | Requirement |
|--------|-------------|
| E1 | Split monolithic `Utils` classes into focused services — `IndexingService`, `ProjectRootsService`, `PsiSearchService`, `RefactoringExecutionService`, `LoggingService`. |
| E2 | Decouple persistence from pipeline execution — move database open/close lifecycle into `EvaluationPersistenceService`; make persistence optional for dry-run mode; fix deprecated MySQL driver class. |
| E3 | Improve logging and reporting — replace `System.out.println` / `e.printStackTrace()` with `com.intellij.openapi.diagnostic.Logger`; add structured log levels (INFO/WARN/ERROR/DEBUG); add operation IDs per integration scenario. |
| E4 | Introduce deterministic timeout policies — extract configurable timeout policy objects for refactoring-detection timeout, PSI sync timeout, cherry-pick/merge timeout, project-opening timeout. |
| E5 | Improve unsupported-refactoring diagnostics — classify skipped refactorings as: unsupported type, unsupported context, missing PSI target, unsafe replay/inversion, ambiguous mapping. |
| E6 | Prepare module structure for future core/platform/integration split — separate pure-Java core (no IntelliJ imports) from platform adapter layer; document module boundaries. |

## Out of Scope (v1)

Pulled verbatim from `PROJECT.md` — these are explicitly deferred:

- New refactoring type support — preserve existing types, don't add new ones.
- UI/UX changes to the plugin action — behavior preserved, not redesigned.
- Database schema changes — persistence structure stays the same.
- RefactoringMiner version upgrade — `2.1.0` stays; API surface is already a risk area.

Additional scope exclusions from research:

- Running the plugin in production against real merge workflows (boss verifies via build artifacts, not runtime).
- Migrating beyond `IC-2024.2.4` target — subsequent platform bumps are out of scope.
- Rewriting the conflict matrix / dispatcher logic — core algorithm preserved.

## Traceability

Every v1 requirement maps to exactly one phase. Phases are numbered sequentially (1–31) to match the GSD framework's integer-phase expectations.

| REQ-ID | Phase | Milestone | Branch |
|--------|-------|-----------|--------|
| P0-01  | Phase 1  | M1 | `task/p0-01-baseline-tag` |
| P0-02  | Phase 2  | M1 | `task/p0-02-environment-manifest` |
| P0-03  | Phase 3  | M1 | `task/p0-03-hotspot-inventory` |
| P0-04  | Phase 4  | M1 | `task/p0-04-regression-scenarios` |
| A1     | Phase 5  | M1 | `task/a1-gradle-upgrade` |
| A2     | Phase 6  | M1 | `task/a2-intellij-platform-plugin-2x` |
| A3     | Phase 7  | M1 | `task/a3-dependency-config-cleanup` |
| A4     | Phase 8  | M1 | `task/a4-plugin-metadata-reconfig` |
| A5     | Phase 9  | M1 | `task/a5-verify-plugin-ci` |
| A6     | Phase 10 | M1 | `task/a6-build-run-docs` |
| B1     | Phase 11 | M2 | `task/b1-application-starter-modern` |
| B2     | Phase 12 | M2 | `task/b2-evaluation-config-record` |
| B3     | Phase 13 | M2 | `task/b3-project-open-waiter` |
| B4     | Phase 14 | M2 | `task/b4-exit-codes-startup-validation` |
| C1     | Phase 15 | M3 | `task/c1-dumbservice-impl-removal` |
| C2     | Phase 16 | M3 | `task/c2-javapsifacade-impl-removal` |
| C3     | Phase 17 | M3 | `task/c3-platform-project-service` |
| C4     | Phase 18 | M3 | `task/c4-vfs-psi-sync-centralization` |
| C5     | Phase 19 | M3 | `task/c5-read-write-action-standardization` |
| C6     | Phase 20 | M3 | `task/c6-module-roots-impl-cleanup` |
| D1     | Phase 21 | M3 | `task/d1-replay-execution-contract` |
| D2     | Phase 22 | M3 | `task/d2-invert-execution-contract` |
| D3     | Phase 23 | M3 | `task/d3-structured-result-types` |
| D4     | Phase 24 | M3 | `task/d4-processor-precondition-validation` |
| D5     | Phase 25 | M3 | `task/d5-per-operation-regression-tests` |
| E1     | Phase 26 | M4 | `task/e1-split-utils-into-services` |
| E2     | Phase 27 | M4 | `task/e2-persistence-decoupling` |
| E3     | Phase 28 | M4 | `task/e3-structured-logging` |
| E4     | Phase 29 | M4 | `task/e4-timeout-policies` |
| E5     | Phase 30 | M4 | `task/e5-unsupported-refactoring-diagnostics` |
| E6     | Phase 31 | M4 | `task/e6-module-structure-prep` |

**Coverage:** 31/31 v1 requirements mapped to 31 phases across 4 milestones. No orphans.
