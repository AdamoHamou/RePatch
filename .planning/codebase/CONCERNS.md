# Concerns

**Analysis Date:** 2026-04-23

## Critical Issues

### Hard-coded credentials in committed files
- `RePatch/database.properties`: MySQL username `root`, password `root`, localhost URL
- `RePatch/github-autho.properties`: GitHub API token
- Both files are tracked in the RePatch git repo — credentials are in version history
- **Risk:** Anyone with repo access has DB and GitHub credentials

### Deprecated Gradle dependencies (`compile` → `implementation`)
- `build.gradle` uses the deprecated `compile` configuration (removed in Gradle 7+) for nearly all dependencies
- Plugin is built with `org.jetbrains.intellij 0.7.3` (2021); current is 1.x/2.x
- `maven` plugin applied (`apply plugin: 'maven'`) — also deprecated since Gradle 7
- Build will break on Gradle 7+ without migration to `implementation`/`api`

### IntelliJ Platform version severely outdated
- Targets `intellij.version '2020.1.2'` (released 2020)
- JetBrains IntelliJ platform APIs evolve rapidly — many PSI and VCS APIs used have changed or been removed
- `git4idea` APIs in particular underwent significant refactoring in 2021–2023
- Plugin will likely not compile against IntelliJ 2022+

## Technical Debt

### No automated tests
- Zero JUnit test classes in `src/test/java/`
- Fixture files in `src/test/resources/` have no harness
- All algorithmic correctness is validated manually / via batch evaluation scripts
- No CI test step in GitHub Actions workflow

### Broad exception catching throughout
- 151 instances of `catch (Exception ...)` or `catch (Throwable ...)` in main sources
- Standard pattern: catch, `e.printStackTrace()`, return `null` or continue
- Lost failures silently pass through the pipeline — callers rarely check for `null` returns
- Example: `RePatch.doMerge` returns `null` on timeout without propagating the reason

### Two `GitUtils` classes with overlapping responsibilities
- `edu.unlv.cs.evol.repatch.utils.GitUtils` — uses IntelliJ `git4idea` API (plugin-side)
- `edu.unlv.cs.evol.integration.utils.GitUtils` — uses JGit directly (CLI-side)
- Nearly identical method names (`checkout`, `commit`, `merge`) with different implementations
- Cross-package confusion risk when both are on the classpath

### Two `Utils` classes
- `edu.unlv.cs.evol.repatch.utils.Utils` — IntelliJ PSI helpers
- `edu.unlv.cs.evol.integration.utils.Utils` — File I/O / process helpers
- Same problem as above — potential import ambiguity

### RefactoringMiner version inconsistency
- Main compile: `refactoring-miner 2.1.0`
- Test compile: `refactoring-miner 2.0` (older version in test scope)
- This creates API skew between production and test code

### Nested `RePatch/RePatch/` directory
- A copy of the repo exists inside itself at `RePatch/RePatch/`
- Likely a legacy artifact; unclear if it's active or stale
- Creates confusion about the authoritative source

## Performance Concerns

### 11-minute hard timeout
- `RePatch.doMerge` uses `Future.get(11, TimeUnit.MINUTES)` for RefactoringMiner
- No progress reporting; if a project has many commits between base and target, the tool silently hangs near-timeout
- No partial-result handling — timeout returns `null` and discards all work done

### Memory-intensive JVM configuration required
- `runIde.jvmArgs '-Xss100m', '-Xmx16g'` — requires 16 GB heap
- Not configurable per-project; unsuitable for CI or shared infrastructure
- Stack size (`-Xss100m`) suggests deep recursion in matrix traversal

### O(N²) conflict matrix
- `Matrix.detectConflicts` compares every pair of refactorings from left and right branches
- For large commits with many refactorings, this grows quadratically
- No early-exit optimization documented

## Security Concerns

### DB credentials in VCS
- See critical issues — `database.properties` with `root/root` is committed
- `github-autho.properties` with API token also committed

### MySQL driver via `com.mysql.jdbc.Driver` (deprecated class)
- Uses the old `com.mysql.jdbc.Driver` JDBC driver class name (deprecated since Connector/J 5.1)
- Should use `com.mysql.cj.jdbc.Driver`

### No input validation on CLI arguments
- `IntegrationPipeline.main` reads `args.get(1)`, `args.get(2)`, `args.get(3)` directly
- No bounds check — `IndexOutOfBoundsException` on short argument lists, caught only by the outer `catch(Throwable)`

## Architecture Concerns

### Tight coupling to IntelliJ Platform
- Core algorithm (`repatch/`) depends deeply on `Project`, `PSI`, `git4idea` APIs
- Impossible to run the algorithm headlessly without a running IntelliJ instance
- Blocks: unit testing, CLI use, Docker automation without a full desktop environment

### Integration harness mixed into same build
- `integration/` package (evaluation pipeline + MySQL ORM) lives in the same artifact as the IntelliJ plugin
- Plugin JAR includes ActiveJDBC, MySQL connector, GitHub API client — unnecessary bloat for end-user plugin
- Ideally separated into a standalone CLI module

### Fragile refactoring order dependency
- `RefactoringOrder` enum controls replay ordering — adding a new refactoring type requires manually assigning order values
- No documentation on ordering constraints; wrong order causes silent merge corruption
