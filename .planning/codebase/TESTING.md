# Testing

**Analysis Date:** 2026-04-23

## Framework

- **JUnit 4.12** (declared in `build.gradle` under `testCompile`)
- **Mockito 2.1.0** (declared but no mock usage found in test resources)
- No test runner configuration (no `@RunWith` classes found in `src/test/java`)

## Test Structure

### No JUnit test classes
`src/test/java/` contains **zero** `.java` files. All testing is resource-fixture-based: the test resources contain Java source pairs that exercise the algorithm's logic cells.

### Fixture-based resources
```
src/test/resources/
├── extractMethodExtractMethodFiles/    ← Tests conflict detection: extract/extract
│   ├── original/                       ← Java files before refactoring
│   └── refactored/                     ← Java files after refactoring
├── extractMethodRenameMethodFiles/
├── extractMethodRenameClassFiles/
├── extractTestData/
├── matrixUtilsTests/
│   ├── original/
│   └── refactored/
├── moveRenameClass/
│   ├── before/
│   └── after/
├── moveRenameMethod/
│   ├── original/
│   └── refactored/
├── refMergeTestData/
├── renameClassRenameClassFiles/
├── renameMethodRenameClassFiles/
│   ├── dependence/
│   ├── dependenceGraph/
│   └── ...
├── renameMethodRenameMethodFiles/
└── renameTestData/
```

Each fixture directory contains pairs of small Java files (usually 1–5 classes) that represent `original` and `refactored` states. These are consumed by the matrix/logic-cell code when run inside the IntelliJ test harness.

## Coverage

- **Unit test coverage:** Effectively zero (no JUnit classes)
- **Integration testing:** Done via `IntegrationPipeline` / `RePatchIntegration` against real GitHub projects (requires MySQL, running IntelliJ IDEA, network)
- **Manual/experimental validation:** The `analysis/` directory holds evaluation results from paper experiments

## Test Execution

Tests require the full IntelliJ platform to be running (plugin test runner via `runIde`):
```bash
./gradlew runIde -Pmode=integration -PdataPath=<path> -PevaluationProject=<name>
```

No `./gradlew test` target is meaningfully configured — the standard Gradle test task would find no test classes.

## Known Testing Gaps

- No automated unit tests for any logic cells, dispatchers, receivers, or invert/replay handlers
- No CI test stage that exercises the algorithm (GitHub Actions only builds, does not run tests)
- Fixture files exist but no harness to run them outside of manual IntelliJ plugin execution
- No property-based or fuzz testing for the refactoring conflict matrix
- Database-backed integration tests require a live MySQL instance

## CI Configuration

`.github/workflows/gradle.yml` (inside `RePatch/` git repo) runs:
```yaml
- uses: actions/checkout@v2
- uses: actions/setup-java@v1
  with:
    java-version: 11
- run: ./gradlew build
```
Build only — no test execution step.
