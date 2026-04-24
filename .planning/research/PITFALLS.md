# Pitfalls Research — Plugin 1.x to 2.x Migration

**Research date:** 2026-04-23
**Overall confidence:** MEDIUM-HIGH for build pitfalls; MEDIUM for git4idea specifics.
> Training-data only — verify LOW/MEDIUM claims against live IntelliJ Platform docs before starting each track.

## Build System Pitfalls

### B-1. Plugin ID is a hard rename, not an upgrade — HIGH
- Old: `id 'org.jetbrains.intellij' version '0.7.3'`
- New: `id 'org.jetbrains.intellij.platform' version '2.x.x'`
- Bumping the version alone silently stays on 1.x (different plugin ID)
- Delete the old `intellij { ... }` extension block entirely

### B-2. Mandatory `intellijPlatform { defaultRepositories() }` in repositories — HIGH
- Old plugin auto-configured repos; new plugin requires explicit declaration
- Without it: "Could not resolve com.jetbrains.intellij.idea:ideaIC"

### B-3. Dependencies move into `dependencies { intellijPlatform { ... } }` — HIGH
- Old `intellij { version = "..."; plugins = ["..."] }` block does not exist in 2.x
- IDE itself is now a Gradle dependency

### B-4. `compile`/`testCompile` removed in Gradle 7 — HIGH
- RePatch uses `compile` throughout
- `compile` → `implementation`, `testCompile` → `testImplementation`, `runtime` → `runtimeOnly`

### B-5. `apply plugin: 'maven'` deleted in Gradle 7 — HIGH
- Build fails: "Plugin with id 'maven' not found"
- Remove the line; RePatch doesn't publish to Maven

### B-6. Gradle wrapper floor: 8.2+ required — HIGH
- RePatch is on Gradle 6.8; plugin 2.x floor is 8.2 (8.5+ recommended)
- `./gradlew wrapper --gradle-version 8.10 --distribution-type bin`

### B-7. Java toolchain jumped to JDK 17 minimum — HIGH
- IntelliJ 2022.2+ compiles against JDK 17; IntelliJ 2024.2+ runs on JDK 21
- Use Gradle toolchain: `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`
- Do NOT leave `sourceCompatibility = 11`

### B-8. Configuration cache: `project` access at execution time — MEDIUM
- 2.x encourages configuration cache; custom Groovy tasks referencing `project` at execution time throw
- Temporary fix: `org.gradle.configuration-cache=false`; proper fix: use `@Input` properties

### B-9. `instrumentationTools()` is now opt-in — MEDIUM
- `@NotNull`/`@Nullable` instrumentation no longer automatic in 2.x
- Add `instrumentationTools()` under `intellijPlatform {}` if needed

### B-10. `runIde` task shape changed — MEDIUM
- Old `runIde { jvmArgs '-Xss100m', '-Xmx16g' }` may be silently ignored
- New: configure via `intellijPlatformTesting { runIde { register('runIde') { task { jvmArgumentProviders... } } } }`

### B-11. `patchPluginXml` property names changed — MEDIUM
- Properties migrate into `intellijPlatform { pluginConfiguration { ideaVersion { ... } } }`

### B-12. ActiveJDBC Gradle plugin 1.2 + Gradle 8.10 compatibility — UNKNOWN
- **Spike required during A1** — orthogonal to IntelliJ migration but could block build

## Removed/Relocated API Classes

### R-1. `DumbServiceImpl` — HIGH
- `com.intellij.openapi.project.DumbServiceImpl` is `@ApiStatus.Internal`; Plugin Verifier flags it
- Replacement: `DumbService.getInstance(project)`
- New APIs since 2022.x: `waitForSmartMode(long)` overload, `runReadActionInSmartMode()`, `smartInvokeLater` changed modality
- **RePatch impact:** Task C1

### R-2. `JavaPsiFacadeImpl` — HIGH
- `com.intellij.psi.impl.JavaPsiFacadeImpl` always was internal; constructor visibility tightened
- Replacement: `JavaPsiFacade.getInstance(project)`
- `new JavaPsiFacadeImpl(project)` fails — constructors are platform-managed
- **RePatch impact:** Task C2

### R-3. `ProjectUtil` (specific methods) — HIGH
- `com.intellij.ide.impl.ProjectUtil` still exists but methods deprecated/moved in 2022.x–2024.x
- `openOrImport(String, Project, boolean)` deprecated → `openOrImportAsync` (Kotlin coroutine) in 2023.x
- `closeAndDispose(Project)` moved to `ProjectManagerEx`
- **Fix:** Prefer `ProjectManager` (`openProject`, `loadAndOpenProject`)
- **RePatch impact:** Task C3 — highest-risk single touchpoint (headless pipeline opens many projects)

### R-4. `com.intellij.ide.impl.*` classes generally — HIGH
- Nothing in `*.impl.*` is stable API by convention
- `OpenProjectTask` became canonical project-open request type; constructor changed multiple times
- Rule: every `com.intellij.ide.impl.X` import is a migration item

### R-5. `VirtualFileManager.syncRefresh()` — HIGH
- Deprecated; causes EDT/threading assertion failures in 2022.x+
- Calling inside a read action throws ("Synchronous refresh is not allowed in a read action")
- **Replacement:** `VfsUtil.markDirtyAndRefresh(async, recursive, reloadChildren, files...)`
- **RePatch impact:** Multiple callsites in `GitUtils`. Task C4.

### R-6. `ServiceManager.getService(...)` — HIGH
- Deprecated 2019, **removed entirely in 2023.1** — won't compile against 2023.1+
- **Fix:** `project.getService(Foo.class)` or `ApplicationManager.getApplication().getService(Foo.class)`

### R-7. `FileDocumentManager.saveAllDocuments()` threading — MEDIUM
- Signature unchanged; threading contract tightened (2022.x+: EDT + write-safe context)
- Fix: `ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> FileDocumentManager.getInstance().saveAllDocuments()))`

### R-8. Refactoring processor constructor churn — MEDIUM
- `RenameProcessor`, `MoveClassesOrPackagesProcessor`, `ExtractMethodProcessor` all gained extra params
- `MoveClassesOrPackagesProcessor` constructor changed in 2022.2 (added `MoveCallback` overload)
- `ExtractMethodProcessor` requires explicit `DataContext` in some paths; preconditions API rewritten
- **RePatch impact:** Task D4 — validate all processor preconditions and constructors

### R-9. `.impl` package replacement map

| Impl Class | Public Replacement | Confidence |
|-----------|-------------------|-----------|
| `DumbServiceImpl` | `DumbService.getInstance(project)` | HIGH |
| `JavaPsiFacadeImpl` | `JavaPsiFacade.getInstance(project)` | HIGH |
| `PsiManagerImpl` | `PsiManager.getInstance(project)` | HIGH |
| `ModuleManagerImpl` | `ModuleManager.getInstance(project)` | HIGH |
| `ProjectRootManagerImpl` | `ProjectRootManager.getInstance(project)` | HIGH |
| `ApplicationImpl` | `ApplicationManager.getApplication()` | HIGH |
| `DocumentImpl` | `Document` (interface) + `FileDocumentManager.getDocument(vfile)` | HIGH |
| `LocalFileSystemImpl` | `LocalFileSystem.getInstance()` | HIGH |
| `GitRepositoryImpl` | `GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)` | HIGH |
| `ProjectUtil` (specific methods) | `ProjectManager` + `ProjectUtil.openOrImportAsync` | MEDIUM |

**General rule:** any import matching `com\.intellij\..*\.impl\..*` or `.*Impl` is a smell. Grep:
```bash
grep -rn "com\.intellij\..*\.impl\." src/main/java
grep -rn "JavaPsiFacadeImpl\|DumbServiceImpl\|ProjectUtil\|\.impl\." src/main/java
grep -rn "import git4idea" src/main/java
```

## ApplicationStarter Status

### AS-1. Still exists; contract changed — HIGH
- 2020.x: `commandName` + `main(List<String>)` or `premain(String[])`. Register via `<applicationStarter>`.
- 2022.x: `premain` deprecated → `main` only; `main(String[])` removed → `main(List<String>)` required
- 2023.x+: `getRequiredModality()` added (return `NOT_IN_EDT`); `isHeadless()` must return `true` for CI
- `ModernApplicationStarter` (Kotlin suspend) is future direction; not required for this migration

**Recommendation: stay on classical `ApplicationStarter` for this migration.**

### AS-2. Args layout in `main(List<String>)` — MEDIUM
- In 2022.x+, `args.get(0)` is the starter's own command name
- Current RePatch reads `args[1]`, `args[2]`, `args[3]` — may be off-by-one on some releases
- Fix: Track B2 typed config object

## git4idea API Changes

### G-1. Service acquisition — MEDIUM
- Old: `GitVcs.getInstance(project)` / `Git.getInstance()` (static)
- New: `project.getService(Git.class)` at project scope

### G-2. GitRepository scan race — HIGH
- In 2022.x+, `GitRepositoryManager.getRepositories()` can return empty immediately after `openProject` — VCS scan still running
- **Symptom:** RePatch opens project then calls `GitUtils.checkout(...)` → gets null repository
- **Fix:** `getRepositoryForRootQuick(root)` with bounded retry, OR register `VcsRepositoryMappingListener` and wait for "mapping updated"

### G-3. Checkout API churn — HIGH
- 2020.x: `GitBrancher.getInstance(project).checkout(name, detached, repos, callback)`
- 2022.x: `GitBrancher` is a service → `project.getService(GitBrancher.class)`; static `getInstance` deprecated ~2022.2
- 2023.x: `GitBrancher.checkout` callback invoked on EDT — problematic for synchronous pipelines
- **Recommended for headless RePatch:** `Git.getInstance().checkout(repository, GitLineHandlerListener, params...)` — version-stable command-wrapper path

### G-4. Cherry-pick API — HIGH
- 2020.x: `GitCherryPicker(project, git, notifier)` directly constructable
- 2022.x: `GitCherryPicker` became service-backed; constructor visibility tightened
- 2023.x: Routes through `VcsCherryPickManager`; `GitCherryPicker` marked internal
- **Recommended replacement:** `Git.getInstance().runCommand(...)` with a `GitLineHandler` for `cherry-pick`
- **RePatch impact:** Core algorithm depends on cherry-pick — plan significant rewrite (Tasks D1/D2)

### G-5. Merge API — HIGH
- 2022.x+: `GitMerger` constructor went package-private; `GitMergeUtil` is recommended but many methods are `@ApiStatus.Internal`
- **Recommended:** `Git.getInstance().runCommand(GitLineHandler(project, root, GitCommand.MERGE))` — version-stable

### G-6. VFS refresh after git operations — HIGH
- 2020.x pattern: git command → `VirtualFileManager.syncRefresh()`
- 2022.x+: platform added automatic VFS-refresh hooks to `GitLineHandler`; `syncRefresh` from read action throws
- Manual refresh may throw; automatic refresh may race — PSI lookup runs before it completes
- **Fix:** `VfsUtil.markDirtyAndRefresh(false, true, true, vfileRoot)` inside `invokeAndWait`, then `PsiDocumentManager.commitAllDocuments()`

## VFS/PSI Synchronization Pitfalls

### VP-1. Read actions required for all PSI access — HIGH
- 2022.x+ **throws** (`Read access is allowed from inside read-action only`) instead of warning
- Every `psiClass.getMethods()` etc. from background thread needs `ReadAction.compute(...)`

### VP-2. Write actions are EDT-only + write-safe context — HIGH
- `WriteAction.run` from background → "Write access is allowed from event dispatch thread only"
- Fix: `ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> { ... }), ModalityState.NON_MODAL)`

### VP-3. Index access during dumb mode — HIGH
- `JavaPsiFacade.findClass(...)` throws `IndexNotReadyException` in dumb mode
- Fix: `DumbService.getInstance(project).runWhenSmart(...)` or `waitForSmartMode()`

### VP-4. Document ↔ PSI ↔ VFS three-way sync order — HIGH
- **For disk-origin changes (git checkout):** VFS refresh → document reload → `commitAllDocuments`
- **For PSI-origin changes (refactorings):** `saveAllDocuments` → disk write
- RePatch's existing `saveAllDocuments` + `syncRefresh` is **backwards for git operations**

### VP-5. Synchronous VFS refresh from background threads — HIGH
- 2022.x+ asserts; `VirtualFileManager.syncRefresh()` off-EDT throws
- Fix: `VfsUtil.markDirtyAndRefresh(false, recursive, reloadChildren, files)` handles dispatch internally

### VP-6. Catching `IndexNotReadyException` and ignoring it — HIGH
- Silent catch is how RePatch ends up with missing refactoring detections
- Correct: re-enter the read action in smart mode, or fail up the stack

## Plugin Verifier Common Failures

### V-1. `INTERNAL_API_USAGE` — HIGH
- Any `*Impl` class or `@ApiStatus.Internal` method
- RePatch will have many on first run — this is Track C's intake list

### V-2. `EXPERIMENTAL_API_USAGE` — MEDIUM
- `@ApiStatus.Experimental` methods that were experimental in 2020.x and since removed

### V-3. `SCHEDULED_FOR_REMOVAL` — MEDIUM
- `@Deprecated(forRemoval = true)` — warnings until removal version, then errors

### V-4. `NoSuchMethodError` / `NoSuchClassError` — HIGH
- `ServiceManager.getService(...)` removed in 2023.1 → compile-time failure against 2023.1+

### V-5. Kotlin stdlib bundled in plugin jar — MEDIUM
- Any transitive Kotlin dep conflicts with IDE's bundled Kotlin stdlib
- Fix: `exclude group: 'org.jetbrains.kotlin'` on transitive deps

### V-6. `plugin.xml` `<idea-version>` bounds stale — HIGH
- `since-build="201"` won't reject verifier but Marketplace warns
- **Recommendation:** Omit `until-build` — current JetBrains recommendation for forward compatibility

### V-7. Missing `<depends>` entries — HIGH
- Using `git4idea.*` without `<depends>Git4Idea</depends>` causes `NoClassDefFoundError` at startup
- Same for `com.intellij.java`

### V-8. ClassLoader leaks on plugin unload — MEDIUM
- 2021.x introduced dynamic unloading; static singletons holding `Project` refs leak the classloader
- RePatch has many utility classes with static state — likely failure
- Fix: `Disposable` parenting; avoid statics holding `Project`

## Per-Phase Risk Notes

### HIGHEST RISK

**Track C — Platform Compatibility Layer (C1–C6)**
- Compilation is easy; runtime threading/modality failures won't appear in a build-only boss check
- Add a headless integration test harness as part of C4/C5 acceptance criteria

**Track D — Refactoring Execution (D1–D5)**
- Processor constructors and preconditions are the least-documented IntelliJ API surface
- Per-refactoring smoke tests required — one big test will hide regressions

### HIGH RISK

**Track B — Startup & Runtime Entry (B1–B4)**
- `ApplicationStarter` contract changes + `ProjectManager.openProject` churn + VCS-repo-scan race (G-2) all intersect here
- **Implement a `ProjectOpenWaiter` utility** that waits for: indexing complete AND VCS repos mapped AND VFS settled

**Track A2/A4/A5**
- A4 sets `sinceBuild`, A5 uses it to pick IDE versions to verify against — wrong A4 makes A5 meaningless
- **Pick one target IDE version for Milestone 1** (recommend 2024.2, `sinceBuild=242`)

### MEDIUM RISK

**Track C3 — Project-opening adapter**
- `openOrImportAsync` is Kotlin-coroutine-based → Java interop needed (expose `CompletableFuture<Project>`)

**Track E — Functional Improvements**
- Gate E1–E6 on C1–C6 merging first; structural churn on top of API churn multiplies errors

### LOW RISK

**Phase 0 — Baseline (P0-01 – P0-04)**
- Risk = doing P0-03 sloppily; P0-03 feeds every subsequent track

**Track A1, A3, A6 — toolchain, deps, docs**
- Mechanical work; dependency conflicts are the main unknown

## Key Recommendations

1. **Do P0-03 first and thoroughly** — grep for all `com.intellij.*`, `git4idea.*`, `*.impl.*` imports; this is the Track C to-do list
2. **Pick one target IDE version** for Milestone 1 (e.g., 2024.2, `sinceBuild=242`); widen only after green
3. **Drop to `Git.getInstance().runCommand(GitLineHandler)` for all git operations** — only version-stable surface for programmatic git in headless contexts; `GitBrancher`, `GitCherryPicker`, `GitMerger` all have moved toward internal
4. **Implement `ProjectOpenWaiter`** to centralize the "indexing + VCS + VFS settled" wait
5. **Track C needs a headless integration-test harness** before any C task is declared done — compile-only acceptance misses threading regressions

---
*Research date: 2026-04-23*
