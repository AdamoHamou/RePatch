# RePatch 2.0 Modernization — Roadmap

**Last updated:** 2026-04-23
**Phases:** 31 (one per WBS task)
**Milestones:** 4 (M1 Build Migration Ready → M2 Runtime Compatibility Ready → M3 Refactoring Engine Stabilized → M4 RePatch 2.0 Candidate)
**Granularity:** fine
**Delivery model:** One branch per phase; each branch must compile and build cleanly before merge.

## Phases

### Milestone 1 — Build Migration Ready (Phases 1–10)

- [ ] **Phase 1: P0-01 Baseline Tag** — Freeze pre-modernization state with `repatch-1.x-baseline` tag.
- [ ] **Phase 2: P0-02 Environment Manifest** — Document JDK, Gradle, IntelliJ, MySQL, and GitHub auth requirements.
- [ ] **Phase 3: P0-03 Hotspot Inventory** — Grep-based migration spreadsheet of `com.intellij.*`, `git4idea.*`, `*.impl.*` imports.
- [ ] **Phase 4: P0-04 Regression Scenarios** — Enumerate known-good end-to-end scenarios to preserve.
- [ ] **Phase 5: A1 Gradle + JDK Upgrade** — Gradle wrapper 8.10, JDK 17 toolchain, `compile`→`implementation`.
- [ ] **Phase 6: A2 IntelliJ Platform Plugin 2.x** — Swap plugin ID, move deps into `intellijPlatform {}`, target `IC-2024.2.4`.
- [ ] **Phase 7: A3 Dependency Config Cleanup** — Final pass on deprecated configs, `runtimeOnly`/`testRuntimeOnly` verification.
- [ ] **Phase 8: A4 Plugin Metadata Reconfig** — `pluginConfiguration {}`, `sinceBuild=242`, `appStarter` extension, `<depends>` entries.
- [ ] **Phase 9: A5 Plugin Verifier + CI** — `verifyPlugin` wired into GitHub Actions with artifact upload.
- [ ] **Phase 10: A6 Build & Run Docs** — README covers `./gradlew runIde` and `./gradlew runIntegrationPipeline`.

### Milestone 2 — Runtime Compatibility Ready (Phases 11–14)

- [ ] **Phase 11: B1 ApplicationStarter Modernization** — Modern signature (`main(List<String>)`, `isHeadless()`, `NOT_IN_EDT`).
- [ ] **Phase 12: B2 Typed EvaluationConfig** — Replace positional args with typed record + layered resolution.
- [ ] **Phase 13: B3 ProjectOpenWaiter** — Wait for indexing + VCS mapping + VFS settled around every `openProject`.
- [ ] **Phase 14: B4 Exit Codes & Startup Validation** — 0/1/2 exit codes + testable `ExitHandler` port.

### Milestone 3 — Refactoring Engine Stabilized (Phases 15–25)

- [ ] **Phase 15: C1 DumbServiceImpl Removal** — Replace with `DumbService.getInstance(project)` modern APIs.
- [ ] **Phase 16: C2 JavaPsiFacadeImpl Removal** — Replace with `JavaPsiFacade.getInstance(project)`.
- [ ] **Phase 17: C3 PlatformProjectService Adapter** — Isolate project-opening APIs behind adapter.
- [ ] **Phase 18: C4 VFS/PSI Sync Centralization** — Correct ordering; eliminate `VirtualFileManager.syncRefresh()`.
- [ ] **Phase 19: C5 Read/Write Action Standardization** — `ReadAction.compute` + `WriteCommandAction.runWriteCommandAction` everywhere.
- [ ] **Phase 20: C6 Module Roots & Impl Cleanup** — Kill remaining `*.impl.*` imports and dead `ServiceManager.getService`.
- [ ] **Phase 21: D1 Replay Execution Contract** — Shared interface + protocol, typed `ReplayResult`.
- [ ] **Phase 22: D2 Invert Execution Contract** — Symmetric to D1, typed `InvertResult`.
- [ ] **Phase 23: D3 Structured Result Types** — Sealed result types throughout; no more null returns or `printStackTrace`.
- [ ] **Phase 24: D4 Processor Precondition Validation** — Fix constructor arity for `RenameProcessor` et al.
- [ ] **Phase 25: D5 Per-Operation Regression Tests** — One replay + invert test per refactoring type.

### Milestone 4 — RePatch 2.0 Candidate (Phases 26–31)

- [ ] **Phase 26: E1 Split Utils into Services** — `IndexingService`, `ProjectRootsService`, `PsiSearchService`, etc.
- [ ] **Phase 27: E2 Persistence Decoupling** — `EvaluationPersistenceService`, optional for dry-run, modern MySQL driver class.
- [ ] **Phase 28: E3 Structured Logging** — `com.intellij.openapi.diagnostic.Logger` with operation IDs.
- [ ] **Phase 29: E4 Timeout Policies** — Configurable policy objects for all long-running operations.
- [ ] **Phase 30: E5 Unsupported-Refactoring Diagnostics** — Classified skip reasons.
- [ ] **Phase 31: E6 Module Structure Prep** — Core / platform / integration boundaries documented.

## Phase Details

### Phase 1: P0-01 Baseline Tag
**Goal**: Freeze the current working state of the RePatch 1.x codebase so every subsequent branch has an unambiguous rollback point.
**WBS Task**: P0-01
**Depends on**: Nothing (first phase)
**Requirements**: P0-01
**Branch**: `task/p0-01-baseline-tag`
**Milestone**: M1
**Success Criteria**:
  1. `git tag --list` shows `repatch-1.x-baseline` pointing at the last commit of the pre-modernization `main`.
  2. Tag is pushed to origin and visible in the remote for the boss.
  3. Repo in a clean state on the baseline commit — no uncommitted changes.
**Plans**: 1 plan
Plans:
- [ ] 01-01-PLAN.md — Verify clean state, create annotated tag repatch-1.x-baseline, configure remote, push tag to origin

### Phase 2: P0-02 Environment Manifest
**Goal**: Produce a written manifest of every environmental prerequisite needed to build, run, and evaluate RePatch today.
**WBS Task**: P0-02
**Depends on**: Phase 1
**Requirements**: P0-02
**Branch**: `task/p0-02-environment-manifest`
**Success Criteria**:
  1. `.planning/env/MANIFEST.md` exists and lists exact JDK version, Gradle version, IntelliJ target version, MySQL version/config, and GitHub auth requirements.
  2. Branch compiles (`./gradlew assemble`) with no code changes — manifest is doc-only.
  3. Boss can follow the manifest on a clean machine and reach the same baseline.
**Plans**: TBD

### Phase 3: P0-03 Hotspot Inventory
**Goal**: Produce a migration spreadsheet classifying every risky import in the codebase so Tracks C and D have scoped targets.
**WBS Task**: P0-03
**Depends on**: Phase 2
**Requirements**: P0-03
**Branch**: `task/p0-03-hotspot-inventory`
**Success Criteria**:
  1. `.planning/inventory/HOTSPOTS.csv` (or `.md`) lists every file importing `com.intellij.*`, `git4idea.*`, or `*.impl.*`, grouped by Track (C1, C2, C3, etc.).
  2. Branch builds cleanly (doc-only change).
  3. Inventory counts agree with `git grep` re-runs the boss performs.
**Plans**: TBD

### Phase 4: P0-04 Regression Scenarios
**Goal**: Capture a checklist of end-to-end scenarios from sample data that must still pass after modernization.
**WBS Task**: P0-04
**Depends on**: Phase 3
**Requirements**: P0-04
**Branch**: `task/p0-04-regression-scenarios`
**Success Criteria**:
  1. `.planning/regression/SCENARIOS.md` enumerates each scenario with inputs, expected outputs, and sample-data path.
  2. Branch builds cleanly.
  3. At least one scenario per supported refactoring type is captured.
**Plans**: TBD

### Phase 5: A1 Gradle + JDK Upgrade
**Goal**: Land the foundational toolchain upgrade — Gradle 8.10 wrapper, JDK 17, modern dependency configurations — without yet swapping the IntelliJ plugin ID.
**WBS Task**: A1
**Depends on**: Phase 4
**Requirements**: A1
**Branch**: `task/a1-gradle-upgrade`
**Success Criteria**:
  1. `./gradlew --version` reports Gradle 8.10 and JVM 17.
  2. `./gradlew assemble` succeeds with `compile`/`testCompile` fully replaced by `implementation`/`testImplementation`.
  3. `apply plugin: 'maven'` is gone and no `compile`-config deprecation warnings surface in the build log.
**Plans**: TBD

### Phase 6: A2 IntelliJ Platform Plugin 2.x
**Goal**: Replace legacy `org.jetbrains.intellij` 0.7.3 with `org.jetbrains.intellij.platform` 2.x targeting `IC-2024.2.4`.
**WBS Task**: A2
**Depends on**: Phase 5
**Requirements**: A2
**Branch**: `task/a2-intellij-platform-plugin-2x`
**Success Criteria**:
  1. `build.gradle(.kts)` uses `org.jetbrains.intellij.platform` 2.x with `intellijPlatform { defaultRepositories() }` and `dependencies { intellijPlatform { intellijIdeaCommunity("2024.2.4") } }`.
  2. `./gradlew buildPlugin` produces a distributable zip.
  3. Old plugin-id references are fully removed — no build-script mentions of `org.jetbrains.intellij` 0.x.
**Plans**: TBD

### Phase 7: A3 Dependency Config Cleanup
**Goal**: Flush remaining deprecated dependency configurations and confirm `runtime`/`testRuntime` paths are correct under Gradle 8.x.
**WBS Task**: A3
**Depends on**: Phase 6
**Requirements**: A3
**Branch**: `task/a3-dependency-config-cleanup`
**Success Criteria**:
  1. `./gradlew dependencies` runs without deprecation warnings on any configuration.
  2. All `runtimeOnly` / `testRuntimeOnly` declarations resolve; no classpath duplication warnings.
  3. `./gradlew buildPlugin` still succeeds.
**Plans**: TBD

### Phase 8: A4 Plugin Metadata Reconfig
**Goal**: Reconfigure plugin metadata through `pluginConfiguration {}` and verify the `appStarter` extension still registers under the new build system.
**WBS Task**: A4
**Depends on**: Phase 7
**Requirements**: A4
**Branch**: `task/a4-plugin-metadata-reconfig`
**Success Criteria**:
  1. `pluginConfiguration { ideaVersion { sinceBuild = "242"; untilBuild = provider { null } } }` present; patched `plugin.xml` has correct `<idea-version>` range.
  2. `appStarter` extension point registration in `plugin.xml` resolves (`IntegrationPipeline` discoverable).
  3. `./gradlew buildPlugin` succeeds and the produced jar's `META-INF/plugin.xml` contains updated `<depends>` entries.
**Plans**: TBD

### Phase 9: A5 Plugin Verifier + CI
**Goal**: Add `verifyPlugin` and wire it into GitHub Actions with uploaded artifacts so the boss can inspect compatibility reports.
**WBS Task**: A5
**Depends on**: Phase 8
**Requirements**: A5
**Branch**: `task/a5-verify-plugin-ci`
**Success Criteria**:
  1. `./gradlew verifyPlugin` runs locally and emits a report under `build/reports/pluginVerifier/`.
  2. `.github/workflows/ci.yml` runs `assemble` + `buildPlugin` + `verifyPlugin` on PR, with the verifier report uploaded as a CI artifact.
  3. The first verifier report is committed (or saved to `.planning/verifier/REPORT-A5.txt`) as intake for Track C.
**Plans**: TBD

### Phase 10: A6 Build & Run Docs
**Goal**: Update `README.md` so a developer (and the boss) can build and run RePatch using the new Gradle tasks.
**WBS Task**: A6
**Depends on**: Phase 9
**Requirements**: A6
**Branch**: `task/a6-build-run-docs`
**Success Criteria**:
  1. `README.md` includes explicit sections for `./gradlew runIde`, `./gradlew runIntegrationPipeline`, `./gradlew buildPlugin`, `./gradlew verifyPlugin`.
  2. Prerequisites (JDK 17, MySQL, GitHub token) are explicitly called out.
  3. `./gradlew buildPlugin` continues to succeed (doc-only branch; no code changes).
**Plans**: TBD

### Phase 11: B1 ApplicationStarter Modernization
**Goal**: Bring `IntegrationPipeline`'s `ApplicationStarter` implementation onto the modern contract so it survives platform 2024.2.
**WBS Task**: B1
**Depends on**: Phase 10
**Requirements**: B1
**Branch**: `task/b1-application-starter-modern`
**Success Criteria**:
  1. `IntegrationPipeline` implements `main(List<String>)`, `isHeadless()` returns `true`, `getRequiredModality()` returns `NOT_IN_EDT`.
  2. `./gradlew buildPlugin` and `./gradlew verifyPlugin` both pass (no new internal-API or signature warnings on `ApplicationStarter`).
  3. `./gradlew runIntegrationPipeline` reaches the first log line of the pipeline with valid args.
**Plans**: TBD

### Phase 12: B2 Typed EvaluationConfig
**Goal**: Replace positional argument parsing with a typed `EvaluationConfig` record that layers CLI > env > defaults with validation.
**WBS Task**: B2
**Depends on**: Phase 11
**Requirements**: B2
**Branch**: `task/b2-evaluation-config-record`
**Success Criteria**:
  1. New `EvaluationConfig` record (pure Java) lives in a package with zero IntelliJ imports.
  2. Missing/invalid config surfaces a friendly validation error containing all offending fields (not a NPE or ArrayIndexOutOfBounds).
  3. `./gradlew buildPlugin` succeeds and unit tests cover layered resolution (CLI overrides env overrides default).
**Plans**: TBD

### Phase 13: B3 ProjectOpenWaiter
**Goal**: Encapsulate the project open/close lifecycle behind a `ProjectOpenWaiter` that blocks until indexing, VCS mapping, and VFS are all settled.
**WBS Task**: B3
**Depends on**: Phase 12
**Requirements**: B3
**Branch**: `task/b3-project-open-waiter`
**Success Criteria**:
  1. All call sites of `openProject` / `openOrImportAsync` route through `ProjectOpenWaiter.openAndAwait(...)`.
  2. `ProjectOpenWaiter` explicitly waits for `DumbService.waitForSmartMode`, non-empty `GitRepositoryManager.getRepositories()`, and a committed VFS state.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 14: B4 Exit Codes & Startup Validation
**Goal**: Add robust exit codes (0/1/2) and an injectable `ExitHandler` so startup validation is testable without killing the JVM.
**WBS Task**: B4
**Depends on**: Phase 13
**Requirements**: B4
**Branch**: `task/b4-exit-codes-startup-validation`
**Success Criteria**:
  1. `ExitHandler` interface used throughout; production impl calls `System.exit`, test impl records the code.
  2. Config validation failures exit with code `2`; runtime errors exit with `1`; success exits with `0`.
  3. `./gradlew buildPlugin` succeeds; unit tests verify each exit code path.
**Plans**: TBD

### Phase 15: C1 DumbServiceImpl Removal
**Goal**: Replace every `DumbServiceImpl` reference with the public `DumbService` API surface.
**WBS Task**: C1
**Depends on**: Phase 14
**Requirements**: C1
**Branch**: `task/c1-dumbservice-impl-removal`
**Success Criteria**:
  1. `git grep DumbServiceImpl` in the modernized source tree returns no hits.
  2. All indexing waits go through `DumbService.getInstance(project).waitForSmartMode()` / `runWhenSmart` / `runReadActionInSmartMode`.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 16: C2 JavaPsiFacadeImpl Removal
**Goal**: Eliminate `JavaPsiFacadeImpl` in favor of `JavaPsiFacade.getInstance(project)` with explicit scopes.
**WBS Task**: C2
**Depends on**: Phase 15
**Requirements**: C2
**Branch**: `task/c2-javapsifacade-impl-removal`
**Success Criteria**:
  1. `git grep JavaPsiFacadeImpl` returns no hits.
  2. Every `findClass` / `findPackage` call passes an explicit `GlobalSearchScope`.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 17: C3 PlatformProjectService Adapter
**Goal**: Hide project-opening APIs behind a `PlatformProjectService` adapter so Ring 3 core has no direct `ProjectUtil`/impl dependency.
**WBS Task**: C3
**Depends on**: Phase 16
**Requirements**: C3
**Branch**: `task/c3-platform-project-service`
**Success Criteria**:
  1. New `PlatformProjectService` interface + impl exist; `ProjectUtil`/impl-class references removed from core.
  2. `openOrImportAsync` Java interop handled cleanly (no raw-type warnings, no unchecked casts).
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 18: C4 VFS/PSI Sync Centralization
**Goal**: Centralize VFS/PSI refresh logic and remove every `VirtualFileManager.syncRefresh()` call.
**WBS Task**: C4
**Depends on**: Phase 17
**Requirements**: C4
**Branch**: `task/c4-vfs-psi-sync-centralization`
**Success Criteria**:
  1. `git grep syncRefresh` returns no hits.
  2. A single `VfsSyncService` exposes the canonical sequence: saveAllDocuments → git op → `VfsUtil.markDirtyAndRefresh` → `commitAllDocuments` → `waitForSmartMode`.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 19: C5 Read/Write Action Standardization
**Goal**: Route all PSI reads through `ReadAction.compute` and all PSI mutations through `WriteCommandAction.runWriteCommandAction`, with correct EDT marshalling from headless threads.
**WBS Task**: C5
**Depends on**: Phase 18
**Requirements**: C5
**Branch**: `task/c5-read-write-action-standardization`
**Success Criteria**:
  1. PSI read/write call sites audited and wrapped; no bare PSI access outside `ReadAction`/`WriteCommandAction` scopes.
  2. EDT marshalling uses `ApplicationManager.getApplication().invokeAndWait` where required.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 20: C6 Module Roots & Impl Cleanup
**Goal**: Kill the remaining `*.impl.*` imports and every `ServiceManager.getService` call (removed in 2023.1).
**WBS Task**: C6
**Depends on**: Phase 19
**Requirements**: C6
**Branch**: `task/c6-module-roots-impl-cleanup`
**Success Criteria**:
  1. `git grep '\.impl\.'` in production source returns no hits (test sources excluded).
  2. `git grep ServiceManager.getService` returns no hits; replaced with `project.getService(X.class)` or `ApplicationManager.getApplication().getService(X.class)`.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 21: D1 Replay Execution Contract
**Goal**: Define a shared execution contract (interface + protocol) for every replay operation.
**WBS Task**: D1
**Depends on**: Phase 20
**Requirements**: D1
**Branch**: `task/d1-replay-execution-contract`
**Success Criteria**:
  1. `RefactoringReplayService` interface defines: precondition validation → PSI target resolution → execute in write context → post-op sync → typed `ReplayResult` return.
  2. At least one existing replay implementation is migrated to the new contract as reference.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 22: D2 Invert Execution Contract
**Goal**: Define the symmetric invert execution contract with typed `InvertResult`.
**WBS Task**: D2
**Depends on**: Phase 21
**Requirements**: D2
**Branch**: `task/d2-invert-execution-contract`
**Success Criteria**:
  1. `RefactoringInvertService` interface mirrors D1's protocol; produces typed `InvertResult`.
  2. One reference implementation migrated.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 23: D3 Structured Result Types
**Goal**: Replace null returns and `e.printStackTrace()` with sealed result hierarchies across the pipeline.
**WBS Task**: D3
**Depends on**: Phase 22
**Requirements**: D3
**Branch**: `task/d3-structured-result-types`
**Success Criteria**:
  1. `ReplayResult`, `InvertResult`, `EvaluationResult`, `ConflictDetectionResult`, `ProjectPreparationResult` all exist as sealed hierarchies (or equivalent).
  2. `git grep printStackTrace` returns no hits in production source.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 24: D4 Processor Precondition Validation
**Goal**: Fix broken constructor arities for IntelliJ refactoring processors and validate preconditions before invocation.
**WBS Task**: D4
**Depends on**: Phase 23
**Requirements**: D4
**Branch**: `task/d4-processor-precondition-validation`
**Success Criteria**:
  1. `RenameProcessor`, `MoveClassesOrPackagesProcessor`, `ExtractMethodProcessor` (and every other processor RePatch uses) invoked with correct modern constructors.
  2. Each invocation site validates preconditions (non-null PSI target, valid scope, etc.) and returns a structured failure instead of throwing.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 25: D5 Per-Operation Regression Tests
**Goal**: Add one replay + one invert test per refactoring type using `LightJavaCodeInsightFixtureTestCase` and before/after fixtures.
**WBS Task**: D5
**Depends on**: Phase 24
**Requirements**: D5
**Branch**: `task/d5-per-operation-regression-tests`
**Success Criteria**:
  1. For every supported refactoring type, `src/test/java/.../refactoring/` contains a replay test and an invert test.
  2. Fixtures live under `src/test/resources/refactoring/<type>/before|after/`.
  3. `./gradlew test` and `./gradlew buildPlugin` both succeed.
**Plans**: TBD

### Phase 26: E1 Split Utils into Services
**Goal**: Break the monolithic `Utils` classes into focused services with single responsibilities.
**WBS Task**: E1
**Depends on**: Phase 25
**Requirements**: E1
**Branch**: `task/e1-split-utils-into-services`
**Success Criteria**:
  1. `IndexingService`, `ProjectRootsService`, `PsiSearchService`, `RefactoringExecutionService`, `LoggingService` exist as distinct classes with narrow interfaces.
  2. Old monolithic `Utils` classes either deleted or reduced to thin delegating shells.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 27: E2 Persistence Decoupling
**Goal**: Move DB open/close lifecycle into `EvaluationPersistenceService`, support dry-run mode, and update the deprecated MySQL driver class.
**WBS Task**: E2
**Depends on**: Phase 26
**Requirements**: E2
**Branch**: `task/e2-persistence-decoupling`
**Success Criteria**:
  1. `EvaluationPersistenceService` owns `openDB` / `closeDB`; pipeline code no longer references ActiveJDBC's `Base` directly.
  2. `EvaluationConfig` dry-run flag bypasses persistence cleanly (no DB connection attempted).
  3. MySQL driver class reference updated to `com.mysql.cj.jdbc.Driver`; `./gradlew buildPlugin` succeeds.
**Plans**: TBD

### Phase 28: E3 Structured Logging
**Goal**: Replace `System.out.println` / `e.printStackTrace()` with `com.intellij.openapi.diagnostic.Logger` and add operation IDs per integration scenario.
**WBS Task**: E3
**Depends on**: Phase 27
**Requirements**: E3
**Branch**: `task/e3-structured-logging`
**Success Criteria**:
  1. `git grep 'System.out.print\|printStackTrace'` in production source returns no hits.
  2. Every integration scenario logs with a stable operation ID tag (`[op=<id>]`) at INFO/WARN/ERROR/DEBUG levels.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 29: E4 Timeout Policies
**Goal**: Extract configurable timeout policy objects for refactoring detection, PSI sync, cherry-pick/merge, and project opening.
**WBS Task**: E4
**Depends on**: Phase 28
**Requirements**: E4
**Branch**: `task/e4-timeout-policies`
**Success Criteria**:
  1. A `TimeoutPolicy` (or equivalent value object) centralizes all timeouts; each has a named, documented default.
  2. Timeouts are overridable via `EvaluationConfig` / env vars.
  3. `./gradlew buildPlugin` succeeds and a unit test covers override precedence.
**Plans**: TBD

### Phase 30: E5 Unsupported-Refactoring Diagnostics
**Goal**: Classify skipped refactorings into well-defined diagnostic categories.
**WBS Task**: E5
**Depends on**: Phase 29
**Requirements**: E5
**Branch**: `task/e5-unsupported-refactoring-diagnostics`
**Success Criteria**:
  1. A `SkipReason` enum (or sealed type) enumerates: unsupported type, unsupported context, missing PSI target, unsafe replay/inversion, ambiguous mapping.
  2. Every skipped refactoring in a pipeline run is logged with a `SkipReason` plus the source commit/ref.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass.
**Plans**: TBD

### Phase 31: E6 Module Structure Prep
**Goal**: Prepare the source tree for a future core / platform / integration split by separating pure-Java core from the IntelliJ adapter layer and documenting the boundaries.
**WBS Task**: E6
**Depends on**: Phase 30
**Requirements**: E6
**Branch**: `task/e6-module-structure-prep`
**Success Criteria**:
  1. Package structure clearly separates `core` (no `com.intellij.*` or `git4idea.*` imports) from `platform` / `integration` layers.
  2. An architecture doc (`.planning/architecture/MODULES.md`) describes layer responsibilities and import rules.
  3. `./gradlew buildPlugin` and `./gradlew verifyPlugin` pass; a static check (shell script or ArchUnit test) verifies core has no IntelliJ imports.
**Plans**: TBD

## Milestone Gates

Each milestone gate requires all phases in the milestone to be merged into `main` with successful CI builds.

### M1 — Build Migration Ready (Phases 1–10)
**Gate**: Plugin builds against IntelliJ Platform Gradle Plugin 2.x, `verifyPlugin` runs in CI, run/build workflow documented. Boss can check out `main` and produce a distributable zip.

### M2 — Runtime Compatibility Ready (Phases 11–14)
**Gate**: `IntegrationPipeline` starts cleanly under 2024.2, typed config replaces positional args, project open/close is deterministic, exit codes are defined.

### M3 — Refactoring Engine Stabilized (Phases 15–25)
**Gate**: No `*.impl.*` imports remain; PSI/VFS/read-write patterns are modern; replay + invert go through shared contracts with typed results; processor constructors compile; one regression test per refactoring type passes.

### M4 — RePatch 2.0 Candidate (Phases 26–31)
**Gate**: Utils split, persistence decoupled, structured logging + timeouts + classified diagnostics live, module boundaries documented. RePatch 2.0 ready for boss sign-off.

## Progress Table

| Phase | WBS | Plans Complete | Status | Completed |
|-------|-----|----------------|--------|-----------|
| 1. P0-01 Baseline Tag | P0-01 | 0/TBD | Not started | - |
| 2. P0-02 Environment Manifest | P0-02 | 0/TBD | Not started | - |
| 3. P0-03 Hotspot Inventory | P0-03 | 0/TBD | Not started | - |
| 4. P0-04 Regression Scenarios | P0-04 | 0/TBD | Not started | - |
| 5. A1 Gradle + JDK Upgrade | A1 | 0/TBD | Not started | - |
| 6. A2 IntelliJ Platform Plugin 2.x | A2 | 0/TBD | Not started | - |
| 7. A3 Dependency Config Cleanup | A3 | 0/TBD | Not started | - |
| 8. A4 Plugin Metadata Reconfig | A4 | 0/TBD | Not started | - |
| 9. A5 Plugin Verifier + CI | A5 | 0/TBD | Not started | - |
| 10. A6 Build & Run Docs | A6 | 0/TBD | Not started | - |
| 11. B1 ApplicationStarter Modernization | B1 | 0/TBD | Not started | - |
| 12. B2 Typed EvaluationConfig | B2 | 0/TBD | Not started | - |
| 13. B3 ProjectOpenWaiter | B3 | 0/TBD | Not started | - |
| 14. B4 Exit Codes & Startup Validation | B4 | 0/TBD | Not started | - |
| 15. C1 DumbServiceImpl Removal | C1 | 0/TBD | Not started | - |
| 16. C2 JavaPsiFacadeImpl Removal | C2 | 0/TBD | Not started | - |
| 17. C3 PlatformProjectService Adapter | C3 | 0/TBD | Not started | - |
| 18. C4 VFS/PSI Sync Centralization | C4 | 0/TBD | Not started | - |
| 19. C5 Read/Write Action Standardization | C5 | 0/TBD | Not started | - |
| 20. C6 Module Roots & Impl Cleanup | C6 | 0/TBD | Not started | - |
| 21. D1 Replay Execution Contract | D1 | 0/TBD | Not started | - |
| 22. D2 Invert Execution Contract | D2 | 0/TBD | Not started | - |
| 23. D3 Structured Result Types | D3 | 0/TBD | Not started | - |
| 24. D4 Processor Precondition Validation | D4 | 0/TBD | Not started | - |
| 25. D5 Per-Operation Regression Tests | D5 | 0/TBD | Not started | - |
| 26. E1 Split Utils into Services | E1 | 0/TBD | Not started | - |
| 27. E2 Persistence Decoupling | E2 | 0/TBD | Not started | - |
| 28. E3 Structured Logging | E3 | 0/TBD | Not started | - |
| 29. E4 Timeout Policies | E4 | 0/TBD | Not started | - |
| 30. E5 Unsupported-Refactoring Diagnostics | E5 | 0/TBD | Not started | - |
| 31. E6 Module Structure Prep | E6 | 0/TBD | Not started | - |

**Coverage:** 31/31 v1 requirements mapped. No orphans, no duplicates.
