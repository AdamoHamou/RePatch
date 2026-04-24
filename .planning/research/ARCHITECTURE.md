# Architecture Research — Modern IntelliJ Plugin Structure

**Research date:** 2026-04-23
**Overall confidence:** MEDIUM — training-data only; verify against live IntelliJ Platform docs before starting Track C.

## Platform Adapter Layer Pattern

### Three-ring architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Ring 3 — Core algorithm (pure Java, no IntelliJ imports)     │
│  edu.unlv.cs.evol.repatch.core.*                              │
│  RefactoringObject, Matrix, ordering, conflict logic          │
└──────────────────────────────────────────────────────────────┘
                    ▲ depends on interfaces only
┌──────────────────────────────────────────────────────────────┐
│  Ring 2 — Platform Facade (interfaces + small DTOs)           │
│  edu.unlv.cs.evol.repatch.platform.api.*                      │
│  Methods return PsiClassRef/FileCoord/RefactoringOutcome,     │
│  never raw PsiClass/VirtualFile                               │
└──────────────────────────────────────────────────────────────┘
                    ▲ implemented by
┌──────────────────────────────────────────────────────────────┐
│  Ring 1 — IntelliJ adapter (impl — only ring that imports     │
│  com.intellij.*, git4idea.*, refactoring processors)          │
│  edu.unlv.cs.evol.repatch.platform.idea.*                     │
│  Registered as @Service beans                                 │
└──────────────────────────────────────────────────────────────┘
```

### Service registration (modern, 2024+)

```java
@Service(Service.Level.PROJECT)
public final class PlatformPsiLookupServiceImpl implements PlatformPsiLookupService {
    private final Project project;
    public PlatformPsiLookupServiceImpl(Project project) { this.project = project; }
}
```

Retrieve: `project.getService(PlatformPsiLookupService.class)`
Interface → impl binding still declared in `plugin.xml`.

### Service map for RePatch

| Interface (Ring 2) | Responsibilities | Ring 1 backing APIs |
|-------------------|-----------------|-------------------|
| `PlatformProjectService` | open/close projects, resolve root, list modules | `ProjectManager`, `ProjectUtil`, `ModuleManager` |
| `PlatformIndexService` | wait-for-smart-mode, run-in-smart-mode | `DumbService` |
| `PlatformPsiLookupService` | find class/method/package by FQN; traverse PSI | `JavaPsiFacade`, `PsiShortNamesCache`, `PsiManager` |
| `PlatformMutationService` | read/write actions; VFS refresh | `ReadAction`, `WriteCommandAction`, `VirtualFileManager` |
| `RefactoringReplayService` | executes replay ops; typed `RefactoringOutcome` | `RefactoringFactory`, `*Processor` classes |
| `RefactoringInvertService` | symmetric: inverts ops | same processor classes |
| `PatchIntegrationService` | Git ops (checkout/cherry-pick/merge) via git4idea | `git4idea.GitUtil`, `Git.getInstance()` |
| `EvaluationPersistenceService` | MySQL persistence, isolated from core | ActiveJDBC (not IntelliJ) |
| `RunConfigurationService` | headless entry wiring, CLI arg parsing, exit codes | `ApplicationStarter` |

## DumbService Modern API

### Core change since 2022.3

`DumbServiceImpl` is internal (`com.intellij.openapi.project.DumbServiceImpl`). Plugin Verifier flags it. Use the public interface:

```java
DumbService dumbService = DumbService.getInstance(project);
```

### Recommended patterns

| Intent | Modern API | Notes |
|--------|-----------|-------|
| Check if indexing in progress | `dumbService.isDumb()` | Cheap, any thread |
| Run once smart (EDT) | `dumbService.runWhenSmart(Runnable)` | Non-blocking |
| Block until smart (headless/tests) | `dumbService.waitForSmartMode()` | Never call on EDT or inside read action |
| Block with timeout | `dumbService.waitForSmartMode(long timeoutMs)` | Returns boolean; preferred for headless |
| Read, retry if dumb | `dumbService.runReadActionInSmartMode(Computable)` | Retries on `IndexNotReadyException` |
| Long read, background | `ReadAction.nonBlocking(...).inSmartMode(project).submit(executor)` | Preferred for pipelines |

### RePatch-specific

Headless `IntegrationPipeline`:
```java
DumbService.getInstance(project).waitForSmartMode(5 * 60_000L);  // 5-min cap
```

Interactive action — never block EDT:
```java
DumbService.getInstance(project).runWhenSmart(() -> { /* continue merge pipeline */ });
```

## PSI Facade Modern API

`JavaPsiFacadeImpl` lives in `com.intellij.psi.impl.JavaPsiFacadeImpl` — internal, not API. Replace all uses:

```java
JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

PsiClass klass  = facade.findClass("com.acme.Foo", GlobalSearchScope.projectScope(project));
PsiPackage pkg  = facade.findPackage("com.acme");
```

### Other lookups

| Need | Modern API |
|------|-----------|
| Find method by name in class | `PsiClass.findMethodsByName(name, checkBases)` |
| Find methods across project | `PsiShortNamesCache.getInstance(project).getMethodsByName(name, scope)` |
| Find classes by short name | `PsiShortNamesCache.getInstance(project).getClassesByName(name, scope)` |
| PsiFile from VirtualFile | `PsiManager.getInstance(project).findFile(virtualFile)` |

### Scope hygiene

Use `GlobalSearchScope.projectScope(project)` or `projectProductionScope` for refactoring targets — not `allScope`. Using `allScope` causes flaky detection when same FQN exists in JDK + project.

### All PSI reads must be inside a read action

```java
return ReadAction.compute(() -> facade.findClass(fqn, scope));
```

EDT callers get an implicit read action. Background/headless code does not.

## VFS Refresh After Checkout

The most error-prone area. Rules changed materially between 2019 and 2024.

### What breaks in current RePatch code

`Utils.java` uses `VirtualFileManager.getInstance().syncRefresh()` and `LocalFileSystem.getInstance().refresh(false)`:
- `syncRefresh()` is allowed **only off EDT and outside a read action** since 2022.x — EDT calls throw or deadlock
- After a git4idea checkout, the platform already fires a refresh — double-refreshing masks bugs

### Modern recipe (2023+)

```java
// 1. Flush PSI edits to disk BEFORE external git op
FileDocumentManager.getInstance().saveAllDocuments();

// 2. Do the git op (checkout/cherry-pick/merge)

// 3. Force VFS to re-scan after disk changed
VfsUtil.markDirtyAndRefresh(/*async*/ false, /*recursive*/ true, /*reloadChildren*/ true, repoRoot);

// 4. Reconcile PSI with VFS
PsiDocumentManager.getInstance(project).commitAllDocuments();

// 5. Wait for indexing to catch up
DumbService.getInstance(project).waitForSmartMode(5 * 60_000L);
```

For headless paths, wrap VFS refresh in EDT call:
```java
ApplicationManager.getApplication().invokeAndWait(() ->
    VfsUtil.markDirtyAndRefresh(false, true, true, repoRoot)
);
```

### Do NOT

- Call `LocalFileSystem.refresh(false)` on EDT
- Call `VirtualFileManager.syncRefresh()` inside a read action (deadlock)
- Rely on `saveAllDocuments()` alone after a git op (it flushes plugin in-memory edits, not disk changes git made)

### Ordering (critical)

```
PSI write (WriteCommandAction)
  └─ FileDocumentManager.saveAllDocuments()      // PSI → disk
       └─ git checkout / cherry-pick / merge      // disk mutated
            └─ VfsUtil.markDirtyAndRefresh(...)   // disk → VFS
                 └─ PsiDocumentManager.commitAllDocuments()   // VFS → PSI
                      └─ DumbService.waitForSmartMode(...)    // PSI → indexes
                           └─ next refactoring
```

## Headless Execution — ApplicationStarter

### State as of 2024

`ApplicationStarter` still exists and is the public headless entry point. Changes since 2020:
- `premain(String[])` deprecated in 2022.x → use `premain(List<String>)`
- `main(String[])` deprecated 2022.x → use `main(List<String>)`
- `getRequiredModality()` added — return `ApplicationStarter.NOT_IN_EDT` for long-running CLI work
- `isHeadless()` must return `true` for CI/headless invocations in 2023.x+
- `ModernApplicationStarter` (Kotlin suspend) is future direction but not required for this migration

### Recommended migration shape for IntegrationPipeline

```java
public final class IntegrationPipelineStarter implements ApplicationStarter {
    @Override public String getCommandName() { return "repatch-integration"; }
    @Override public int getRequiredModality() { return ApplicationStarter.NOT_IN_EDT; }
    @Override public boolean isHeadless() { return true; }

    @Override
    public void main(List<String> args) {
        IntegrationConfig cfg = IntegrationConfig.parse(args);
        try {
            RePatchIntegration.run(cfg);
            ApplicationManagerEx.getApplicationEx().exit(true, false, 0);
        } catch (Exception e) {
            LOG.error("integration run failed", e);
            ApplicationManagerEx.getApplicationEx().exit(true, false, 2);
        }
    }
}
```

**Stay on classical `ApplicationStarter` for this migration** — no simpler drop-in replacement exists for a Java plugin that needs PSI/indexes.

## Read/Write Action Patterns

### Rules (2024)

| Thread | Can read PSI? | Can write PSI? |
|--------|--------------|----------------|
| EDT | Yes (implicit) | Yes, inside `WriteCommandAction.runWriteCommandAction` |
| Background | Only inside `ReadAction.run/compute` | Never directly — marshal to EDT first |
| NonBlocking pooled | Via `ReadAction.nonBlocking(...)` | Same as background |

### Read action

```java
// Short, sync:
PsiClass klass = ReadAction.compute(() ->
    JavaPsiFacade.getInstance(project).findClass(fqn, scope));

// Long, background with cancellation (preferred for pipelines):
ReadAction.nonBlocking(() -> doExpensivePsiWork(project))
    .inSmartMode(project)
    .expireWith(disposable)
    .submit(AppExecutorUtil.getAppExecutorService());
```

### Write action

All PSI mutations (including refactoring processor calls) MUST go through `WriteCommandAction`:

```java
WriteCommandAction.runWriteCommandAction(project, "RePatch: Invert Move Method", "RePatch",
    () -> {
        MoveStaticMembersProcessor processor = new MoveStaticMembersProcessor(...);
        processor.run();
    });
```

### Invoking on EDT from headless

```java
ApplicationManager.getApplication().invokeAndWait(() ->
    WriteCommandAction.runWriteCommandAction(project, () -> {
        // PSI mutations
    })
);
```

Always use `invokeAndWait` (not `invokeLater`) in batch mode so ordering against subsequent git ops is preserved.

### Minimal PlatformMutationService interface

```java
public interface PlatformMutationService {
    <T> T readInSmart(Supplier<T> body);              // background-safe
    void writeCommand(String title, Runnable body);   // EDT-marshalled
    <T> T writeCommand(String title, Supplier<T> body);
    void refreshVfs(VirtualFile root, boolean recursive);
    void commitDocuments();                           // PSI ↔ VFS sync
    void saveAllDocuments();                          // Document → disk
}
```

## Smart Mode / Indexing Wait Patterns

### Decision tree

```
Can I do the work lazily (retry if dumb)?
  └─ Yes → ReadAction.nonBlocking(...).inSmartMode(project).submit(...)
  └─ No  → Must block. On what thread?
            ├─ EDT → DumbService.runWhenSmart(callback)   (non-blocking)
            └─ Background/headless → DumbService.waitForSmartMode(timeout)
```

### Timeout fallback (if waitForSmartMode(long) not available at target build)

```java
long deadline = System.currentTimeMillis() + 5 * 60_000L;
while (DumbService.getInstance(project).isDumb()) {
    if (System.currentTimeMillis() > deadline) return Result.indexingTimeout();
    Thread.sleep(250);
}
```

### What to avoid

- `ApplicationManager.getApplication().runReadAction(...)` in a dumb check loop — holds read lock and prevents indexing from advancing
- Calling `waitForSmartMode()` on EDT — deadlock
- Catching `IndexNotReadyException` and ignoring it — leads to missing refactoring detections

## Confidence Notes

| Section | Confidence | Notes |
|---------|-----------|-------|
| Platform adapter layer | MEDIUM | Pattern is sound; `@Service(Level)` stable since 2022.3; verify import paths |
| DumbService API | MEDIUM | `getInstance`, `runWhenSmart`, `waitForSmartMode` are long-standing; timeout overload needs build-version verification |
| PSI Facade API | HIGH | `JavaPsiFacade` has been the public API since IDEA 9.x |
| VFS refresh | LOW-MEDIUM | Direction is correct; exact API names may have shifted; verify against target platform source |
| Headless execution | LOW | Fastest-changing area; treat code sample as template, not known-correct |
| Read/write action patterns | MEDIUM-HIGH | Threading rules extremely well documented and stable |
| Smart mode patterns | MEDIUM | Stable direction; verify timeout overload at target build |

---
*Research date: 2026-04-23*
