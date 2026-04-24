# Research Summary — RePatch 2.0 Modernization

**Research date:** 2026-04-23

## Key Findings

**Stack:** Migrate from `org.jetbrains.intellij` 0.7.3 to `org.jetbrains.intellij.platform` 2.x (different plugin ID — hard rename, not a bump). Requires Gradle 8.5+, JDK 17+, target `IC-2024.2.4` first. Dependency declarations move into `dependencies { intellijPlatform { ... } }`. Task names: `verifyPlugin` (was `runPluginVerifier`), `patchPluginXml` still exists but config moves to `pluginConfiguration {}`.

**Table Stakes:** Each branch must compile and build cleanly. First `verifyPlugin` run will surface dozens of `INTERNAL_API_USAGE` errors — that output IS Track C's to-do list.

**Watch Out For:**
1. **git4idea is the single highest-risk API** — `GitCherryPicker`, `GitBrancher`, `GitMerger` have all moved toward `@ApiStatus.Internal`. Safe path: `Git.getInstance().runCommand(GitLineHandler)` for all git ops.
2. **`VirtualFileManager.syncRefresh()` crashes at runtime** (not compile time) when called inside a read action on 2022.x+ — RePatch's current patterns will hit this in production even if the build passes.
3. **VCS repo-scan race** — `GitRepositoryManager.getRepositories()` returns empty immediately after `openProject` on 2024.x. Implement a `ProjectOpenWaiter` that waits for indexing + VCS map + VFS settled before proceeding.
4. **`ServiceManager.getService(...)` was removed in 2023.1** — won't compile.
5. **ActiveJDBC Gradle plugin 1.2 + Gradle 8.10 compatibility is unknown** — spike required in A1.
6. **`ApplicationStarter` still works** for Java plugins but: `main(String[])` removed → use `main(List<String>)`; `isHeadless()` must return `true`; `getRequiredModality()` must return `NOT_IN_EDT`.

## Architecture Recommendation

Three-ring adapter pattern:
- **Ring 3 (Core):** pure Java — `Matrix`, refactoring objects, ordering, conflict logic. Zero IntelliJ imports.
- **Ring 2 (API):** interfaces only — `PlatformProjectService`, `PlatformIndexService`, `PlatformPsiLookupService`, `PlatformMutationService`, `RefactoringReplayService`, `RefactoringInvertService`.
- **Ring 1 (Impl):** only ring that imports `com.intellij.*` and `git4idea.*`. All `*.impl.*` classes eliminated here.

## Testing Recommendation

| Layer | Framework | Task |
|-------|-----------|------|
| Core algorithm (matrix, ordering, config) | Plain JUnit 4 | `./gradlew test` |
| PSI/processor tests | `LightJavaCodeInsightFixtureTestCase` | `./gradlew test` |
| Project lifecycle, VFS, git | `HeavyPlatformTestCase` | `./gradlew testIde` |
| Full pipeline E2E | Custom heavy fixtures + sample data | `./gradlew endToEndTest` |

Use sealed result types (`ReplayResult`, `InvertResult`, `EvaluationResult`) over null returns. Use `com.intellij.openapi.diagnostic.Logger` — `LOG.error` fails tests, so use `LOG.warn` for expected failures.

## Phase Risk Summary

| Track | Risk | Key concern |
|-------|------|-------------|
| A (Build) | High for A2/A4 | Three stacked migrations — ActiveJDBC unknown |
| B (Startup) | High for B1/B3 | `ApplicationStarter` contract + VCS scan race |
| C (Platform compat) | HIGHEST | Runtime threading failures won't show in build-only check |
| D (Refactoring) | Highest for D1/D2 | git4idea cherry-pick/merge APIs moved internal |
| E (Functional) | Medium | Gate on C completion to avoid compounding churn |

## Research Files

- `STACK.md` — full build migration guide with before/after `build.gradle`
- `ARCHITECTURE.md` — DumbService, PSI facade, VFS refresh, read/write action patterns
- `FEATURES.md` — testing framework, result types, logging, config object patterns  
- `PITFALLS.md` — 30+ specific pitfalls with confidence ratings and per-phase risk notes
