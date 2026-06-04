# Refactoring Execution Contract (Week 4A)

Every replay and invert operation runs through
`RefactoringExecutionService.execute(RefactoringOperation)`, which drives six
steps:

| # | Step | Owner |
|---|------|-------|
| 1 | Precondition validation (project alive, dumb tasks drained, operation's own checks) | service + operation `prepare` |
| 2 | PSI target resolution through the adapter layer (`PsiSearchService`, never bare `JavaPsiFacade`/`PsiManager`) | operation `prepare` |
| 3 | Processor invocation in the correct context (processors take their own write actions; invoked directly or via `PlatformFacade.invokeAndWait` from the EDT) | operation `execute` |
| 4 | Post-operation synchronization (`VfsSyncService.commitAndReparse`) | service |
| 5 | Structured result capture (`RefactoringExecutionResult`) | service |
| 6 | Failure classification (`PRECONDITION_FAILED` / `PROCESSOR_THREW` / `POSTCONDITION_BROKEN` / `UNSUPPORTED_SHAPE`) | service |

The dispatchers (`InvertRefactorings`, `ReplayRefactorings`) log one
classified `[RefactoringExecution] <STATUS> <operation> — <detail>` line per
failure via `Utils.log`, so the per-PR
"`N refactorings were not inverted`" summary is now attributable to causes.
Operation classes not yet migrated run through `RefactoringOperation.legacy`,
which still provides Throwable-safe capture and post-operation sync, but
cannot classify the legacy bodies' silent `return` paths.

## Threading rules (2024.x headless path)

`IntegrationPipeline.main` is dispatched on the EDT. The contract relies on:

- **PSI reads**: `PlatformFacade.runInSmartReadAction` →
  `DumbService.runReadActionInSmartMode`. Synchronous, EDT-safe, waits for
  the index. This eliminated the post-checkout `IndexNotReadyException`
  flood (1310/run before Week 4).
- **PSI writes**: `PlatformFacade.runWriteCommand` →
  `WriteCommandAction.runWriteCommandAction`.
- **Processors**: invoked via `PlatformFacade.invokeAndWait`; never wrapped
  in an outer write action (they take their own).
- **Never** `runWhenSmart` + `Future.get()` — deadlocks on the EDT
  (the helper was deleted in Week 4).

## Operation families

### Move/rename class — `InvertMoveRenameClass` (migrated), `ReplayMoveRenameClass` (legacy)
- **PSI inputs**: destination class by qualified name + file path; for moves,
  the original package (`findPackage`) or enclosing class
  (`findClassByFilePath`); for top-level moves with no existing target
  directory, the common package prefix between the current directory and the
  original package.
- **Side effects**: class renamed and/or moved (inner→outer, outer→inner,
  inner→inner, package→package); references and imports updated by the
  platform processors; target directory chain created if missing; containing
  virtual file refreshed.
- **Failure behavior**: missing targets → `PRECONDITION_FAILED` before any
  mutation; RefMiner package truncation (no common package) →
  `UNSUPPORTED_SHAPE`; processor blowups → `PROCESSOR_THREW` (IntelliJ
  processors are not transactional — a partial rename is possible and is
  recorded in the result).
- **Logs**: classified result line on failure.
- **Postcondition**: class resolvable under its original qualified name from
  the original file path.

### Move/rename method — `InvertMoveRenameMethod`, `ReplayMoveRenameMethod` (both migrated)
- **PSI inputs**: containing class from the relevant file path; method
  located by RefactoringMiner signature comparison
  (`PsiSearchService.findMethod`); for replay moves, the recorded
  method-above anchor.
- **Side effects**: method renamed and/or moved between classes; invert
  records the method-above anchor on the refactoring object; replay
  repositions the moved method body using that anchor.
- **Failure behavior**: unresolvable class/method → `PRECONDITION_FAILED`;
  processor blowups → `PROCESSOR_THREW`.
- **Logs**: classified result line on failure.
- **Postcondition**: method resolvable under the expected signature on the
  expected class.

### Extract/inline — `InvertExtractMethod` (migrated), `ReplayExtractMethod`, `InvertInlineMethod`, `ReplayInlineMethod` (legacy)
- **PSI inputs**: class containing source + extracted method; the extracted
  method's invocation site inside the source method (`ReferencesSearch`,
  smart-read-wrapped); thrown-exception info from the extracted method.
- **Side effects**: invert inlines the extracted method and deletes its
  declaration, recording surrounding-statement smart pointers and thrown
  exceptions on the refactoring object so replay can re-extract the same
  region.
- **Failure behavior**: unresolvable class/method/invocation surroundings →
  `PRECONDITION_FAILED`; processor blowups → `PROCESSOR_THREW`.
- **Logs**: classified result line on failure.
- **Postcondition** (invert): extracted method no longer present on the
  class.

### Pull-up / push-down (methods and fields) — all legacy
- **PSI inputs**: superclass and subclass(es) by qualified name + file path;
  members located by signature/name; `MemberInfo` arrays built via
  `Utils.getMembersToPullUp`/`getFieldsToPullUp`.
- **Side effects**: members moved along the hierarchy via `PullUpProcessor`
  / push-down processors; method duplicates processed
  (`Utils.processMethodsDuplicates`).
- **Failure behavior**: legacy — exceptions classify as `PROCESSOR_THREW`
  through the legacy adapter; missing-target silent returns are not yet
  classified (migration pending).
- **Postcondition** (target state when migrated): member present on the
  expected class and absent from the source class.

### Parameter edits (rename parameter) — legacy
- **PSI inputs**: containing class + method by signature; parameter by name
  (`PsiSearchService.findParameter`).
- **Side effects**: parameter renamed; usages inside the method body
  updated.
- **Failure behavior**: legacy adapter classification only (migration
  pending).
- **Postcondition** (target): parameter with the expected name present on
  the method signature.

### Package rename — legacy
- **PSI inputs**: package by qualified name (`findPackage`).
- **Side effects**: package renamed; directories and references updated.
- **Failure behavior**: legacy adapter classification only (migration
  pending).
- **Postcondition** (target): package resolvable under the expected name.

## Testing (Week 4C)

`InMemoryPlatformFacade` (in `src/test/java`) is the facade double: direct
synchronous execution for threading primitives, safe stubs for IDE surfaces,
counters for sync assertions. `RefactoringExecutionServiceTest` covers all
five classification paths; `InvertMoveRenameMethodTest` is the per-operation
regression pattern — detect a refactoring from a `src/test/resources` fixture
pair with RefactoringMiner, run the operation through the contract, assert
the PSI ends up matching the `expected*Results` fixture. Propagate that
pattern as further operation classes are migrated.
