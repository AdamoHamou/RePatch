# Features Research — Plugin Quality & Testing Patterns

**Research date:** 2026-04-23
**Overall confidence:** MEDIUM — training-data only; verify exact DSL names against the 2.x plugin version pinned in A2.

## Testing Framework for 2.x Plugins

**Recommendation:** JUnit 4 + IntelliJ Platform test framework (`com.intellij.testFramework.*`).

JUnit 5 is supported but second-class for plugins extending `BasePlatformTestCase` / `LightPlatformTestCase` — those are JUnit 3/4-style `TestCase` subclasses.

**Why JUnit 4 for RePatch:**
- IntelliJ-provided fixtures extend `junit.framework.TestCase` (JUnit 3 lineage) — work natively under JUnit 4
- JUnit 5 needs `junit-vintage-engine` + `JUnit5TestFrameworkType` wiring to use IntelliJ fixtures
- JetBrains Community source uses JUnit 4 — matches reviewer expectations

**Gradle configuration (IntelliJ Platform Gradle Plugin 2.x):**

```groovy
dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)   // for PsiJavaFile-aware fixtures
    }
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.assertj:assertj-core:3.26.3'
    testImplementation 'org.mockito:mockito-core:5.+'
}
```

**Test tasks:**
- `test` — standard unit tests (no IDE classpath needed)
- `testIde` / per-fixture tasks via `intellijPlatformTesting.testIde { ... }` — run inside sandboxed IDE
- Bosses `./gradlew test` for fast compile checks; CI also runs `testIde` suite

## Unit Test Setup (No IntelliJ Context Needed)

RePatch has significant pure-Java logic that should NOT touch IntelliJ APIs:
- Matrix conflict-cell lookups (`dispatcher/`, `receivers/`, `logicCells/`)
- Refactoring-object normalization and equality
- Ordering/topological sort of refactoring sequences
- Result classification (conflict severity, failure categories)
- Typed config parsing (replacing positional argv)

Plain JUnit 4 under the standard `test` task — no IDE sandbox, sub-second startup.

```java
public class ConflictMatrixTest {
    @Test
    public void extractMethodVsRenameMethod_onDisjointScopes_producesNoConflict() {
        ConflictMatrix matrix = ConflictMatrix.defaultMatrix();
        RefactoringOp left  = Fixtures.extractMethod("A.foo", "A.bar");
        RefactoringOp right = Fixtures.renameMethod("B.baz", "B.qux");

        ConflictResult result = matrix.evaluate(left, right);

        assertThat(result.severity()).isEqualTo(Severity.NONE);
        assertThat(result.diagnostics()).isEmpty();
    }
}
```

**Keeping pure logic pure:** anything unit-testable without the IDE must not import `com.intellij.*` or `git4idea.*`. This is the payoff of Track E6 (module split prep) — but don't wait for E6; extract PSI-free DTOs during D1/D2.

**Mocking policy:** prefer fakes and in-memory doubles over Mockito mocks. Matrix/ordering logic is too combinatorial to mock meaningfully — test with real inputs.

## Platform Integration Test Setup

### Two base classes

**`LightJavaCodeInsightFixtureTestCase` (preferred when sufficient)**
- Shared project across tests — fast
- Provides `myFixture` (`JavaCodeInsightTestFixture`) for writing files, invoking refactorings
- Override `getProjectDescriptor()` to add JDK, source roots, Git roots
- Constraint: cannot simulate "checkout branch and re-index from scratch"

```java
public class RenameMethodProcessorTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected String getTestDataPath() {
        return "src/test/resources/renameMethodRenameMethodFiles";
    }

    public void testRenameMethodProducesExpectedPsi() {
        myFixture.configureByFile("original/A.java");
        PsiMethod method = findMethod(myFixture.getFile(), "foo");
        new RenameProcessor(getProject(), method, "bar", false, false).run();
        myFixture.checkResultByFile("refactored/A.java");
    }
}
```

**`HeavyPlatformTestCase` (when you need real project lifecycle)**
- Each test gets its own `Project` in a temp directory
- Required for: open project → index → Git checkout → re-resolve PSI → verify VFS refresh
- Significantly slower — use sparingly in a dedicated `testIde` task

### intellijPlatformTesting DSL for test task splitting

```kotlin
intellijPlatformTesting {
    testIde.register("integrationTest") {
        task {
            systemProperty("java.awt.headless", "true")
            include("**/*IntegrationTest.class")
        }
    }
    testIde.register("endToEndTest") {
        task {
            include("**/*E2ETest.class")
            jvmArgs("-Xmx4g")
        }
    }
}
```

Boss runs `./gradlew test` for fast checks; CI runs `./gradlew testIde integrationTest endToEndTest`.

### TestFixtureBuilder pattern

For tests needing non-trivial project structure:

```java
IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
TestFixtureBuilder<IdeaProjectTestFixture> builder =
    factory.createFixtureBuilder("repatch-git-scenario", true);
builder.addModule(JavaModuleFixtureBuilder.class)
       .addSourceContentRoot(tempDir.resolve("src").toString())
       .addLibrary("jgit", jgitJar.toString());
IdeaProjectTestFixture fixture = builder.getFixture();
fixture.setUp();
try {
    // test code against fixture.getProject()
} finally {
    fixture.tearDown();
}
```

### Recommended test taxonomy for RePatch

| Layer | Base class | Gradle task | Purpose |
|-------|-----------|-------------|---------|
| Unit (core) | plain JUnit 4 | `test` | Matrix cells, ordering, normalization, result classification, config parsing |
| Platform-light | `LightJavaCodeInsightFixtureTestCase` | `test` | Single refactoring processor invocation, PSI shape checks |
| Platform-heavy | `HeavyPlatformTestCase` | `testIde.integrationTest` | Project open/close, VFS refresh, Git checkout + PSI re-resolution |
| End-to-end | custom `HeavyPlatformTestCase` + fixture scenarios | `testIde.endToEndTest` | Full invert → cherry-pick → replay on sample data |

## Testing Refactoring Processors

**Golden pattern: data-driven before/after**

`configureByFile(before)` / `checkResultByFile(after)` — aligns with existing `original/` + `refactored/` fixture layout in `src/test/resources/`.

```java
public void testExtractMethodReplayRegeneratesRefactoredForm() {
    myFixture.configureByFiles("original/Service.java", "original/Client.java");
    RefactoringDescriptor descriptor = loadDescriptor("extractMethodDescriptor.json");

    ReplayResult result = replayService.replay(descriptor, myFixture.getProject());

    assertThat(result.outcome()).isEqualTo(ReplayOutcome.APPLIED);
    myFixture.checkResultByFile("Service.java", "refactored/Service.java", true);
}
```

**Three invocation considerations:**
1. Processors must run under `WriteCommandAction.runWriteCommandAction(getProject(), () -> processor.run())`
2. `processor.run()` registers undoable commands — in tests sharing a project, clear undo state if needed
3. Processors assume smart mode — `DumbService.getInstance(project).waitForSmartMode()` before invocation; never `DumbServiceImpl`

**Precondition-failure tests (Track D4):**

```java
public void testRenameMethodFailsPreconditionWhenTargetNameIsJavaKeyword() {
    myFixture.configureByText("A.java", "class A { void foo() {} }");
    PsiMethod method = findMethod(myFixture.getFile(), "foo");

    ReplayResult result = replayService.rename(method, "class", myFixture.getProject());

    assertThat(result.outcome()).isEqualTo(ReplayOutcome.SKIPPED_PRECONDITION_FAILED);
    assertThat(result.diagnostic()).contains("Java keyword");
}
```

**Isolating RefactoringMiner from IntelliJ tests:**
- RefactoringMiner produces descriptors from git commits — no IntelliJ needed
- Wrap behind a `RefactoringDetectionPort`; test the adapter with plain JUnit against small JGit repos
- Downstream replay/invert tests use pre-recorded RefactoringMiner JSON output as fixtures

## Testing Headless Execution

### Layer 1: test business logic without the starter

Refactor `IntegrationPipeline` so `main(args)` delegates to a plain class taking typed config + ports. The starter becomes ~10 lines. The runner is unit-testable with plain JUnit + fakes. This directly supports B2 (typed config object).

```java
public interface HeadlessEvaluationRunner {
    ExitCode run(EvaluationConfig config, RunnerPorts ports);
}
```

### Layer 2: smoke-test the ApplicationStarter end-to-end

`HeavyPlatformTestCase` that:
1. Opens small sample project from `src/test/resources/refMergeTestData/`
2. Constructs the starter and calls `main(args)` with synthesized args
3. Asserts exit code, log output, persisted results (against in-memory persistence fake)

Use `testIde.register("starterSmokeTest")` with `-Djava.awt.headless=true`.

**Headless-specific pitfalls:**
- `System.exit` from a starter terminates the test JVM — introduce `ExitHandler` port (Track B4)
- Every `openProject` must have a matching close in try/finally or a `ProjectScope` helper (Track B3)
- Modal dialogs even in headless mode — inject `ConflictResolutionPolicy` port with deterministic decision
- `-Djava.awt.headless=true` mandatory — IntelliJ test framework sets it; ad-hoc runners do not

## Structured Result Type Patterns

**Recommendation: sealed result hierarchy (requires JDK 17+ from A1)**

```java
public sealed interface ReplayResult
        permits ReplayResult.Applied, ReplayResult.Skipped, ReplayResult.Aborted, ReplayResult.Error {

    RefactoringId refactoringId();

    record Applied(RefactoringId refactoringId, Duration elapsed, List<PsiChange> changes)
            implements ReplayResult {}

    record Skipped(RefactoringId refactoringId, SkipReason reason, String diagnostic)
            implements ReplayResult {}

    record Aborted(RefactoringId refactoringId, ConflictSeverity severity, List<String> conflicts)
            implements ReplayResult {}

    record Error(RefactoringId refactoringId, String message, Throwable cause)
            implements ReplayResult {}
}
```

**Why sealed:** exhaustive `switch` — compiler flags any new failure category. Track D3.

**If pinned to JDK 11:** use a closed enum of outcome kinds + companion DTO. Not recommended given A1 upgrades to JDK 17.

**Aggregate result:**

```java
public record EvaluationResult(
    EvaluationId id,
    List<ReplayResult> replayResults,
    List<InvertResult> invertResults,
    MergeOutcome mergeOutcome,
    Instant startedAt,
    Duration totalElapsed
) {}
```

## Logging Patterns

**Recommendation: `com.intellij.openapi.diagnostic.Logger` everywhere.**

```java
public final class ReplayService {
    private static final Logger LOG = Logger.getInstance(ReplayService.class);

    public ReplayResult replay(RefactoringOp op) {
        LOG.info("Replaying " + op.id() + " kind=" + op.kind());
        try {
            // ...
            LOG.debug("Replay applied in " + elapsed + "ms");
            return new ReplayResult.Applied(op.id(), elapsed, changes);
        } catch (RefactoringConflictException e) {
            LOG.warn("Replay aborted for " + op.id() + ": " + e.getMessage());
            return new ReplayResult.Aborted(op.id(), Severity.HIGH, e.conflicts());
        } catch (Exception e) {
            LOG.error("Replay failed for " + op.id(), e);
            return new ReplayResult.Error(op.id(), e.getMessage(), e);
        }
    }
}
```

**Why `Logger` (not SLF4J, not `println`):**
- Routes to `idea.log` — accessible via `Help | Show Log in Finder`
- Respects per-category log-level configuration at runtime
- Universal IntelliJ plugin convention

**Critical gotcha:** In `BasePlatformTestCase`, `LOG.error(...)` **fails tests**. Genuine expected failure paths → use `LOG.warn` or typed `ReplayResult.Error`. Don't use `LOG.error` for expected scenarios.

**Do not use:** `println`, `System.out`, `java.util.logging`, `e.printStackTrace()`

## Configuration Object Patterns

**Replace positional args with typed record (Track B2):**

```java
public record EvaluationConfig(
    Path dataPath,
    String evaluationProject,
    Mode mode,
    int parallelism,
    Duration perOperationTimeout,
    Optional<GitCredentials> gitCredentials,
    LogLevel logLevel
) {
    public enum Mode { INTEGRATION, DRY_RUN, REPLAY_ONLY }

    public static EvaluationConfig fromArgs(List<String> args) { ... }
    public static EvaluationConfig fromEnvironment(Map<String, String> env) { ... }
}
```

**Layered resolution (CLI > env vars > defaults):**

```java
public static EvaluationConfig parseAndValidate(List<String> args) {
    EvaluationConfig cfg = fromArgs(args);
    List<String> errors = new ArrayList<>();
    if (!Files.exists(cfg.dataPath())) errors.add("dataPath does not exist: " + cfg.dataPath());
    if (cfg.evaluationProject().isBlank()) errors.add("evaluationProject is required");
    if (!errors.isEmpty()) throw new ConfigValidationException(errors);
    return cfg;
}
```

Validation failure → exit code `2`; runtime failure → `1`; success → `0`. Feeds Track B4.

**CLI parser choice for RePatch's ~5-flag surface:** hand-written parser. Upgrade to picocli only if args grow past ~10 flags.

## Confidence Notes

| Area | Confidence | Notes |
|------|-----------|-------|
| JUnit 4 as primary runner | HIGH | `BasePlatformTestCase` is JUnit 3 lineage, runs under JUnit 4 |
| 2.x Gradle DSL shapes | MEDIUM-HIGH | Core shapes stable; exact DSL tokens may vary by 2.x minor — verify at A2 |
| Light/heavy test case split | HIGH | Long-standing convention; unchanged in 2.x |
| `testIde.register(...)` extension | MEDIUM | DSL is current; method names vary across 2.0/2.1/2.2 — verify against pinned version |
| `TestFixtureBuilder` pattern | HIGH | Stable API, used in platform tests |
| Refactoring processor testing | HIGH | `configureByFile`/`checkResultByFile` is canonical |
| ApplicationStarter testing approach | MEDIUM | "Extract runner, smoke-test starter as thin adapter" is sound design, not documented pattern |
| Sealed result hierarchy | HIGH (if JDK 17+) | Standard modern Java; requires A1 JDK upgrade to land first |
| Logger recommendation | HIGH | Universal IntelliJ plugin convention |
| `LOG.error` fails tests | HIGH | Documented behavior, known community gotcha |
| Typed config over positional args | HIGH | Standard Java practice |

---
*Research date: 2026-04-23*
