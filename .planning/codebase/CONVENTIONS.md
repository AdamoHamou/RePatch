# Conventions

**Analysis Date:** 2026-04-23

## Language & Runtime

- **Language:** Java (target: Java 8+)
- **Build system:** Gradle (Groovy DSL)
- **IDE framework:** IntelliJ Platform SDK (plugin runs inside IntelliJ IDEA)

## Code Style

### Formatting
- Standard Java formatting; no automated formatter enforced (though `google-java-format` is in the dependency list but not wired into build)
- Indentation: 4-space (consistent throughout)
- Opening braces on same line as statement

### Naming
- Classes: `UpperCamelCase` (e.g. `MoveRenameMethodObject`, `ExtractMethodDispatcher`)
- Methods/variables: `lowerCamelCase`
- Constants: `UPPER_SNAKE_CASE`
- Packages: all lowercase, domain-reversed (`edu.unlv.cs.evol.repatch`)
- Naming pattern is structural/descriptive: class names encode refactoring type + role (e.g. `RenamePackageMoveRenameMethodCell`)

### Imports
- No wildcard imports; explicit class imports throughout

## Design Patterns

### Double Dispatch (Visitor) — conflict matrix
- `Dispatcher` interface has a single `dispatch(Receiver r)` method
- Each `*Dispatcher` implementation calls the matching method on the `*Receiver`
- Each `*Receiver` routes to the appropriate `*Cell` class
- Enables N×N refactoring-pair handling without giant switch statements in a single class

### Strategy / Singleton map — type routing
- `Matrix.dispatcherMap` is a static `HashMap<RefactoringType, RefactoringDispatcher>` used as a registry
- `InvertRefactorings` and `ReplayRefactorings` use `switch` statements over `RefactoringType` to dispatch to handlers

### Immutable-ish domain objects
- `RefactoringObject` interface exposes getters/setters; in practice objects are created once and mutated sparingly (only `setReplayFlag`)

## Error Handling

- Most operations catch `Exception` broadly and `e.printStackTrace()` — no structured error types or logging framework
- Timeouts are handled via `Future.get(11, TimeUnit.MINUTES)` in `RePatch.doMerge`; timeout causes `return null`
- IntelliJ API failures (VCS operations) are swallowed silently or print a message via `System.out.println`
- Database errors in `IntegrationPipeline` catch `Throwable` and `System.exit(1)`

```java
// Typical error handling pattern throughout codebase
try {
    git = Git.open(dir);
} catch (IOException ioException) {
    ioException.printStackTrace();
}
```

## Comments

- Javadoc-style block comments on public methods (but not all methods have them)
- `/* ... */` multi-line comments on methods; `//` inline comments sparse
- No `@param` / `@return` tags — comment bodies describe behavior in prose

```java
/*
 * Gets the directory of the project that's being merged, then it calls the function that performs the merge.
 */
public ArrayList<...> refMerge(...)
```

## Threading

- `ExecutorService.newSingleThreadExecutor()` used in `RePatch.doMerge` to run RefactoringMiner with a timeout
- IntelliJ VCS operations (checkout, commit, add) run in inner `Thread` subclasses (`GitThread`, `GitAdd`, `DoGitCommit`) and are `join()`-ed
- No thread pools; each operation spawns a dedicated thread

## Dependency Injection

- None. IntelliJ `Project` object is passed explicitly through call chains
- `Matrix` receives `Project` in its constructor; helpers are constructed inline

## Logging

- No logging framework — `System.out.println` throughout
- `Utils.log(projectName, message)` writes to a log file for integration results

## Constants & Configuration

- DB credentials hard-coded in `database.properties` (`root`/`root`) — read by ActiveJDBC at startup
- GitHub token in `github-autho.properties`
- JVM args in `build.gradle` (`runIde.jvmArgs '-Xss100m', '-Xmx16g'`)
- No config abstraction layer — properties files read directly
