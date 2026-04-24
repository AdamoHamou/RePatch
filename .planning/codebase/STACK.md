# Technology Stack

**Analysis Date:** 2026-04-23

## Languages

**Primary:**
- Java 11 - All source code in `RePatch/src/main/java/`

**Secondary:**
- SQL - Schema definition in `RePatch/src/main/resources/create_integration_schema.sql`
- Python - Data analysis scripts in `RePatch/analysis/notebook.ipynb`

## Runtime

**Environment:**
- JVM (Java Virtual Machine), Java 11 (OpenJDK 11)
- Required JVM flags: `-Xss100m -Xmx16g` for `runIde` task; build uses `-Xss1024M -Xms4G -Xmx8G` (configured in `RePatch/gradle.properties`)

**Package Manager:**
- Gradle 6.8 (Gradle Wrapper)
- Wrapper config: `RePatch/gradle/wrapper/gradle-wrapper.properties`
- Lockfile: Not present (no `gradle.lockfile`)

## Frameworks

**Core:**
- IntelliJ Platform SDK (IntelliJ IDEA 2020.1.2) - RePatch is packaged as an IntelliJ IDEA plugin; all refactoring operations use IntelliJ PSI API and IntelliJ VCS APIs
- ActiveJDBC 2.2 (`org.javalite:activejdbc`) - ORM layer for all database model classes in `RePatch/src/main/java/edu/unlv/cs/evol/integration/database/`

**Testing:**
- JUnit 4.12 (`junit:junit`) - Unit tests (test source resources under `RePatch/src/test/resources/`)
- Mockito 2.1.0 (`org.mockito:mockito-core`) - Mocking in tests

**Build/Dev:**
- Gradle IntelliJ Plugin 0.7.3 (`org.jetbrains.intellij`) - Builds and runs the IDEA plugin
- ActiveJDBC Gradle Plugin 1.2 (`de.schablinski.activejdbc-gradle-plugin`) - Instruments ActiveJDBC model classes at build time

## Key Dependencies

**Critical:**
- `org.eclipse.jgit:org.eclipse.jgit:5.10.0` - Low-level Git operations (clone, fetch, cherry-pick, merge) used throughout `RePatch/src/main/java/edu/unlv/cs/evol/repatch/` and `integration/` packages
- `com.github.tsantalis:refactoring-miner:2.1.0` - Detects refactorings between commits; central to the entire tool's function; used in `RePatch.java` via `GitHistoryRefactoringMiner`
- `org.eclipse.jdt:org.eclipse.jdt.core:3.24.0` - Java AST/parsing support; provided as both Maven dep and local JAR in `RePatch/lib/org.eclipse.jdt.core-3.24.0.jar`
- `IntelliMerge-1.0.7-modified.jar` - Modified IntelliMerge JAR stored locally in `RePatch/lib/` and `RePatch/` root; used as a comparison baseline (currently disabled/commented out in pipeline)

**Infrastructure:**
- `mysql:mysql-connector-java:8.0.16` - MySQL JDBC driver (runtime only)
- `org.javalite:activejdbc:2.2` + `activejdbc-instrumentation:2.2` - ORM for all database entities
- `org.kohsuke:github-api:1.135` - GitHub REST API client; used in `GitHubUtils.java` to retrieve pull requests and merge commit SHAs
- `io.reflectoring.diffparser:diffparser:1.4` - Parses unified diff output for conflict block extraction in `EvaluationUtils.java`
- `com.google.googlejavaformat:google-java-format:1.7` - Java source code formatter
- `com.github.ertugrulcetin:CommentRemover:1.2` - Strips comments from Java source files during evaluation
- `commons-cli:commons-cli:1.4` - Command-line argument parsing
- `org.apache.commons.lang3` (transitive via IntelliJ) - Used for `Pair` data structure throughout the codebase
- `git4idea` (bundled IntelliJ plugin) - IntelliJ's native Git integration; used alongside JGit for checkout, reset, cherry-pick, and history operations

## Configuration

**Environment:**
- Database connection configured via environment variables at runtime (read in `DatabaseUtils.java`):
  - `JDBC_URL` - Full JDBC connection URL (defaults to `jdbc:mysql://localhost/refactoring_aware_integration_repatch?serverTimezone=UTC`)
  - `JDBC_URL_WITHOUT_DATABASE` - Base JDBC URL without DB name (defaults to `jdbc:mysql://localhost?serverTimezone=UTC`)
  - `JDBC_USER` - Database username (defaults to `root`)
  - `JDBC_PASSWORD` - Database password (defaults to `root`)
- GitHub OAuth token loaded from `github-oauth.properties` on classpath (property key: `OAuthToken`); root-level placeholder at `RePatch/github-autho.properties`
- `LEFT_COMMIT`, `RIGHT_COMMIT`, `BASE_COMMIT` environment variables used in `RePatch.actionPerformed()` for headless/IDE action mode
- IDE run mode driven by Gradle property `-Pmode=integration` with `-PdataPath` and `-PevaluationProject`

**Build:**
- `RePatch/build.gradle` - Main build file; defines plugins, repositories, dependencies, and `runIde` task configuration
- `RePatch/gradle.properties` - JVM memory settings for Gradle daemon
- `RePatch/settings.gradle` - Sets root project name to `RePatch`
- `RePatch/src/main/resources/META-INF/plugin.xml` - IntelliJ plugin descriptor; registers `IntegrationPipeline` as `appStarter` extension with command name `integration`
- `RePatch/src/main/resources/repatch_database/database.properties` - ActiveJDBC database config (used by ActiveJDBC framework for connection; partially superseded by environment variables)

## Platform Requirements

**Development:**
- Java 11 (OpenJDK 11)
- Gradle 6.8 (via wrapper)
- IntelliJ IDEA 2020.1.2 (specific version required by plugin SDK; download handled by Gradle IntelliJ plugin)
- RefactoringMiner 2.1.0 must be published to Maven local (see `RePatch/docker/dev-container-repatch/Dockerfile` for build instructions)
- MySQL 8.0 database accessible at configured JDBC URL

**Production:**
- Deployed as an IntelliJ IDEA plugin (`runIde` Gradle task) or executed headlessly via `gradle runIde -Pmode=integration`
- Docker development environment available at `RePatch/docker/dev-container-repatch/` using `lscr.io/linuxserver/webtop:ubuntu-kde` base image with IntelliJ IDEA 2020.1.2 pre-installed
- Docker Compose stack (`RePatch/docker/dev-container-repatch/docker-compose.yml`) includes: app container (VNC-accessible), MySQL 8.0, and phpMyAdmin

---

*Stack analysis: 2026-04-23*
