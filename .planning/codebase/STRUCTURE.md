# Directory Structure

**Analysis Date:** 2026-04-23

## Top-Level Layout

```
Repatch_Modernization/           ← GSD workspace root (new git repo)
└── RePatch/                     ← Original project (has its own git repo)
    ├── src/
    │   ├── main/
    │   │   ├── java/edu/unlv/cs/evol/
    │   │   │   ├── integration/   ← Evaluation harness + DB layer
    │   │   │   └── repatch/       ← Core algorithm (IntelliJ plugin)
    │   │   └── resources/
    │   │       ├── repatch_database/   ← ActiveJDBC SQL schema files
    │   │       └── META-INF/           ← plugin.xml, IntelliJ manifest
    │   └── test/
    │       ├── java/              ← (no test classes — tests use resources)
    │       └── resources/         ← Java fixture pairs (original/refactored)
    ├── analysis/                  ← Evaluation output CSVs / scripts
    ├── database-dump/             ← MySQL dump files for seeding
    ├── docker/                    ← Dev container (IntelliJ + MySQL desktop)
    ├── figures/                   ← Paper figures
    ├── lib/                       ← Local JARs (IntelliMerge, PaReco)
    ├── RePatch/                   ← Nested copy of the repo (legacy/reference)
    ├── build.gradle               ← Gradle build config
    ├── gradle.properties
    ├── settings.gradle
    ├── database.properties        ← DB credentials (dev: root/root)
    ├── github-autho.properties    ← GitHub API token
    └── IntelliMerge-1.0.7-modified.jar ← Patched IntelliMerge binary
```

## Core Source Packages

### `edu.unlv.cs.evol.repatch` — Core Plugin

| Package | Location | Role |
|---------|----------|------|
| `repatch/` | `src/main/java/.../repatch/` | Entry point `RePatch.java` (extends `AnAction`) |
| `repatch/refactoringObjects/` | `...repatch/refactoringObjects/` | Domain model — one object class per refactoring type |
| `repatch/refactoringObjects/typeObjects/` | `...typeObjects/` | Fine-grained type model: `ClassObject`, `MethodSignatureObject`, `ParameterObject` |
| `repatch/invertOperations/` | `...invertOperations/` | Undo each refactoring type on the PSI tree |
| `repatch/replayOperations/` | `...replayOperations/` | Re-apply each refactoring type after merge |
| `repatch/matrix/` | `...matrix/` | Conflict detection orchestrator (`Matrix.java`) |
| `repatch/matrix/dispatcher/` | `...dispatcher/` | Double-dispatch: one `*Dispatcher` per refactoring type |
| `repatch/matrix/receivers/` | `...receivers/` | Double-dispatch: one `*Receiver` per refactoring type |
| `repatch/matrix/logicCells/` | `...logicCells/` | Conflict logic: one `*Cell` class per refactoring-pair combination |
| `repatch/utils/` | `...repatch/utils/` | `GitUtils`, `MatrixUtils`, `RefactoringObjectUtils`, `Utils` |

### `edu.unlv.cs.evol.integration` — Evaluation Harness

| Package | Location | Role |
|---------|----------|------|
| `integration/` | `...integration/` | `IntegrationPipeline.java` (CLI via `ApplicationStarter`), `RePatchIntegration.java` |
| `integration/data/` | `...integration/data/` | In-memory result models: `ComparisonResult`, `ConflictBlockData`, etc. |
| `integration/database/` | `...integration/database/` | ActiveJDBC ORM models: `Project`, `Patch`, `MergeCommit`, `Refactoring`, etc. |
| `integration/utils/` | `...integration/utils/` | `GitHubUtils`, `GitUtils`, `EvaluationUtils`, `Utils` |

## Key File Locations

| File | Purpose |
|------|---------|
| `RePatch/src/main/java/.../repatch/RePatch.java` | Plugin action entry point; `actionPerformed` drives the merge |
| `RePatch/src/main/java/.../integration/IntegrationPipeline.java` | CLI batch evaluation runner |
| `RePatch/src/main/java/.../integration/RePatchIntegration.java` | Runs comparisons against real GitHub projects |
| `RePatch/src/main/java/.../repatch/matrix/Matrix.java` | Conflict matrix; owns the `dispatcherMap` |
| `RePatch/src/main/resources/META-INF/plugin.xml` | IntelliJ plugin descriptor |
| `RePatch/database.properties` | MySQL connection credentials |
| `RePatch/build.gradle` | Gradle config (IntelliJ plugin + ActiveJDBC) |

## Naming Conventions

- **Refactoring object classes:** `[Type]Object.java` — e.g. `MoveRenameMethodObject`, `ExtractMethodObject`
- **Invert handlers:** `Invert[Type].java` — e.g. `InvertMoveRenameClass`, `InvertExtractMethod`
- **Replay handlers:** `Replay[Type].java` — e.g. `ReplayMoveRenameMethod`, `ReplayExtractMethod`
- **Matrix dispatchers:** `[Type]Dispatcher.java` — e.g. `MoveRenameMethodDispatcher`
- **Matrix receivers:** `[Type]Receiver.java` — e.g. `MoveRenameMethodReceiver`
- **Matrix logic cells:** `[TypeA][TypeB]Cell.java` — e.g. `ExtractMethodMoveRenameMethodCell`

## Adding a New Refactoring Type

To add support for a new refactoring type, touch these locations in order:
1. `refactoringObjects/` — new `[Type]Object.java` implementing `RefactoringObject`
2. `refactoringObjects/RefactoringObjectUtils.java` — add factory case
3. `invertOperations/` — new `Invert[Type].java` + register in `InvertRefactorings`
4. `replayOperations/` — new `Replay[Type].java` + register in `ReplayRefactorings`
5. `matrix/dispatcher/` — new `[Type]Dispatcher.java` + register in `Matrix.dispatcherMap`
6. `matrix/receivers/` — new `[Type]Receiver.java`
7. `matrix/logicCells/` — new `[Type][OtherType]Cell.java` for each existing type pair

## Test Resources Structure

Tests use pre-built Java file pairs (no JUnit test classes exist):
```
src/test/resources/
├── extractMethodExtractMethodFiles/   ← original/ + refactored/ Java files
├── extractMethodRenameMethodFiles/
├── matrixUtilsTests/
├── moveRenameClass/
├── moveRenameMethod/
├── refMergeTestData/
├── renameClassRenameClassFiles/
├── renameMethodRenameClassFiles/
├── renameMethodRenameMethodFiles/
├── renameTestData/
└── extractTestData/
```
Each directory contains `original/` and `refactored/` subdirectories with Java source files for fixture-based testing.
