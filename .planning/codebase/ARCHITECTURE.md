# Architecture

**Analysis Date:** 2026-04-23

## Pattern Overview

**Overall:** IntelliJ Platform Plugin with a layered pipeline architecture

**Key Characteristics:**
- Runs as an IntelliJ IDEA plugin; all operations are invoked through the IntelliJ Platform API (`AnAction`, `ApplicationStarter`, PSI)
- Core algorithm follows a three-phase pipeline: Invert refactorings → Git merge/cherry-pick → Replay refactorings
- Refactoring conflict detection uses the **Double Dispatch (Visitor) pattern** — a Dispatcher fires at a Receiver, routing to a specific logic cell
- The `integration` package is a separate evaluation harness that drives the `repatch` core against real GitHub projects and stores results in MySQL

## Layers

**Core Algorithm Layer (`repatch` package):**
- Purpose: Implements the refactoring-aware merge algorithm
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/`
- Contains: Entry point (`RePatch.java`), invert/replay operation handlers, conflict matrix, refactoring domain objects, utilities
- Depends on: IntelliJ Platform API, RefactoringMiner, JGit, git4idea
- Used by: `integration` package (via `RePatchIntegration.runRefMerge`)

**Refactoring Object Model (`refactoringObjects` package):**
- Purpose: Domain model for each supported refactoring type
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/refactoringObjects/`
- Contains: `RefactoringObject` interface, concrete objects per type (e.g. `MoveRenameMethodObject`, `ExtractMethodObject`), `RefactoringOrder` enum, type sub-objects (`MethodSignatureObject`, `ClassObject`, `ParameterObject`)
- Depends on: RefactoringMiner API types
- Used by: All other layers; the universal currency passed between invert, matrix, and replay

**Conflict Matrix Layer (`matrix` package):**
- Purpose: Detects conflicts and ordering dependencies between pairs of refactorings; simplifies transitive chains
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/matrix/`
- Contains: `Matrix.java` (orchestrator), `dispatcher/` (one class per refactoring type), `receivers/` (one class per refactoring type), `logicCells/` (one class per pair of refactoring types)
- Depends on: `refactoringObjects`, IntelliJ PSI
- Used by: `RePatch.doMerge` (both for simplification during detection and for conflict detection before replay)

**Invert Operations Layer (`invertOperations` package):**
- Purpose: Reverses each refactoring type on the IntelliJ PSI/source tree
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/invertOperations/`
- Contains: `InvertRefactorings.java` (dispatcher via switch), one handler per type (e.g. `InvertMoveRenameClass`, `InvertExtractMethod`)
- Depends on: `refactoringObjects`, IntelliJ Platform refactoring API, PSI
- Used by: `RePatch.doMerge`

**Replay Operations Layer (`replayOperations` package):**
- Purpose: Re-applies each refactoring type after the base merge completes
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/replayOperations/`
- Contains: `ReplayRefactorings.java` (dispatcher via switch), one handler per type (e.g. `ReplayMoveRenameMethod`, `ReplayExtractMethod`)
- Depends on: `refactoringObjects`, IntelliJ Platform refactoring API, PSI
- Used by: `RePatch.doMerge`

**Utilities Layer (`repatch/utils` package):**
- Purpose: Git operations via IntelliJ git4idea API, PSI helpers, refactoring object factory
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/utils/`
- Contains: `GitUtils.java` (checkout, commit, cherry-pick, merge, diff), `Utils.java` (PSI refresh, VFS, logging), `RefactoringObjectUtils.java` (factory + ordered insertion), `MatrixUtils.java` (PSI inheritance checks)
- Depends on: IntelliJ Platform API, git4idea, JGit
- Used by: All layers above

**Integration / Evaluation Layer (`integration` package):**
- Purpose: Batch evaluation harness — iterates GitHub projects, applies RePatch, records results to MySQL
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/integration/`
- Contains: `IntegrationPipeline.java` (CLI entry via `ApplicationStarter`), `RePatchIntegration.java` (orchestration), `database/` (ActiveJDBC models), `data/` (plain data transfer objects), `utils/` (Git, GitHub API, evaluation helpers)
- Depends on: `repatch` core, MySQL via ActiveJDBC, JGit, GitHub API (`kohsuke/github-api`), IntelliJ Platform
- Used by: Gradle `runIde` task with `-Pmode=integration`

## Data Flow

**Primary Merge Flow:**

1. `RePatch.actionPerformed` or `RePatchIntegration.runRefMerge` receives left/right/base commit SHAs
2. `RePatch.detectAndSimplifyRefactorings` calls RefactoringMiner between base and each branch commit; transitive refactorings are combined via `Matrix.simplifyAndInsertRefactorings`; results are `ArrayList<RefactoringObject>`
3. IntelliJ checks out the right commit; `InvertRefactorings.invertRefactorings` reverses each right-branch refactoring on the PSI tree; git add+commit produces `rightUndoCommit`
4. IntelliJ checks out the left commit; `InvertRefactorings.invertRefactorings` reverses each left-branch refactoring on the PSI tree; git add+commit
5. `GitUtils.cherryPick(rightUndoCommit)` applies the inverted right branch to the inverted left branch; conflicts are detected
6. `Matrix.detectConflicts(leftRefs, rightRefs)` performs pairwise double-dispatch comparison; conflicting pairs are flagged `isReplay=false`; ordered replay list is built using `RefactoringOrder`
7. `ReplayRefactorings.replayRefactorings` re-applies all non-conflicting refactorings from the combined list in `RefactoringOrder` sequence
8. `FileDocumentManager.saveAllDocuments()` flushes PSI changes to disk

**Integration Evaluation Flow:**

1. `IntegrationPipeline.main` parses CLI args, opens DB connection, calls `startEvaluation`
2. `RePatchIntegration.runComparison` reads `sample_data/repatch_integration_projects`; for each project clones/opens it via IntelliJ `ProjectUtil`
3. `evaluateProject` reads `sample_data/repatch_integration_patches`; for each PR: fetches merge commit SHA via GitHub API; constructs left/right/base commit triple
4. `evaluateMergeScenario` runs Git cherry-pick for baseline, then calls `runRefMerge` (invoking `RePatch.refMerge`)
5. Conflict block data is extracted from both tool outputs; all results (MergeCommit, MergeResult, ConflictingFile, ConflictBlock, Refactoring) are persisted to MySQL via ActiveJDBC

**State Management:**
- No shared mutable state between merge calls; all state is local to the call stack
- IntelliJ `Project` object is threaded through every layer as a parameter
- Git state is managed by IntelliJ git4idea API; operations run on dedicated threads (inner `Thread` subclasses in `GitUtils`) to avoid EDT blocking

## Key Abstractions

**RefactoringObject (interface):**
- Purpose: Common contract for all refactoring domain objects — carries original/destination file paths, type, replay flag, ordering
- Examples: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/refactoringObjects/RefactoringObject.java`
- Pattern: Interface with concrete implementations per type; factory in `RefactoringObjectUtils.createRefactoringObject`

**Matrix (conflict/simplification engine):**
- Purpose: Centralizes all pairwise refactoring interaction logic; routes pairs to the correct logic cell via double dispatch
- Examples: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/matrix/Matrix.java`
- Pattern: Double Dispatch — `dispatcherMap` and `receiverMap` keyed by `RefactoringType`; dispatcher calls `receiver.receive(this)` which routes to the specific `*Cell` class

**LogicCell classes:**
- Purpose: Encode the conflict/transitivity rules for one specific pair of refactoring types
- Examples: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/matrix/logicCells/MoveRenameMethodMoveRenameMethodCell.java`
- Pattern: Each cell handles: conflict detection (naming, override, overload), ordering dependence, and transitivity combination

**RefactoringOrder (enum):**
- Purpose: Defines the canonical replay ordering across all refactoring types (package renames first, extract method last)
- Examples: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/refactoringObjects/RefactoringOrder.java`
- Pattern: Integer-ordered enum; `RefactoringObjectUtils.insertRefactoringObject` uses it for sorted insertion

**ActiveJDBC Models (integration):**
- Purpose: ORM models mapping to MySQL tables for evaluation data persistence
- Examples: `RePatch/src/main/java/edu/unlv/cs/evol/integration/database/MergeCommit.java`, `MergeResult.java`, `ConflictBlock.java`, `Project.java`, `Patch.java`
- Pattern: ActiveJDBC `Model` subclasses; `saveIt()` / `findFirst()` pattern throughout `RePatchIntegration`

## Entry Points

**IntelliJ Plugin Action (`RePatch.java`):**
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/repatch/RePatch.java`
- Triggers: Invoked as an IntelliJ `AnAction` (Tools menu); also callable programmatically via `refMerge()`
- Responsibilities: Reads `LEFT_COMMIT`, `RIGHT_COMMIT`, `BASE_COMMIT` env vars; orchestrates the full merge pipeline

**Integration Pipeline CLI (`IntegrationPipeline.java`):**
- Location: `RePatch/src/main/java/edu/unlv/cs/evol/integration/IntegrationPipeline.java`
- Triggers: Gradle `runIde` task with `-Pmode=integration -PdataPath=... -PevaluationProject=...`; registered as `ApplicationStarter` in `plugin.xml`
- Responsibilities: Parses CLI args, initializes MySQL database schema, delegates to `RePatchIntegration`

## Error Handling

**Strategy:** Best-effort with fallback counters; exceptions are caught per-refactoring and logged via `printStackTrace`; failed refactorings are counted but do not abort the full merge

**Patterns:**
- `InvertRefactorings` and `ReplayRefactorings` wrap each individual refactoring call in `try/catch(Exception)`; failed count returned for logging
- `RePatch.doMerge` uses a 15-minute timeout via `ExecutorService.submit().get(11, TimeUnit.MINUTES)`; `TimeoutException` returns `null`
- Integration layer catches `AssertionError | OutOfMemoryError | LargeObjectException.OutOfMemory` around `RePatch.refMerge`; records `-1` runtime as sentinel value in DB
- Git operations run on dedicated threads with `thread.join()` to ensure completion before next step

## Cross-Cutting Concerns

**Logging:** `System.out.println` throughout; no structured logging framework. Integration results logged to `Utils.log(projectName, message)` writing to a file
**Validation:** None — `RefactoringObjectUtils.createRefactoringObject` returns `null` for unsupported types; callers check for `null` before proceeding
**Authentication:** GitHub API token read from `github-autho.properties` file via `GitHubUtils`; DB credentials read from `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` env vars with hardcoded fallbacks to `root`/`root`

---

*Architecture analysis: 2026-04-23*
