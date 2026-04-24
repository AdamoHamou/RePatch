# External Integrations

**Analysis Date:** 2026-04-23

## APIs & External Services

**Source Control Hosting:**
- GitHub REST API - Retrieves pull request metadata (merge commit SHA, author, timestamp, PR status)
  - SDK/Client: `org.kohsuke:github-api:1.135`
  - Auth: OAuth token loaded from `github-oauth.properties` classpath resource (property key: `OAuthToken`); falls back to anonymous connection
  - Implementation: `RePatch/src/main/java/edu/unlv/cs/evol/integration/utils/GitHubUtils.java`
  - Methods used: `getPullRequestsInRepo()`, `getMergeCommitSha()`, `getMergeCommitShaList()`, `getMergeCommitOfPR()`
  - Supports both `https://github.com/` and `https://bitbucket.org/` URL parsing (Bitbucket support is partial; no Bitbucket API client)

**Refactoring Detection:**
- RefactoringMiner 2.1.0 - Detects Java refactoring operations between two commits in a Git repository
  - SDK/Client: `com.github.tsantalis:refactoring-miner:2.1.0` (must be installed to Maven local from source; not on Maven Central)
  - Auth: None (operates on local Git repositories)
  - Implementation: Called in `RePatch/src/main/java/edu/unlv/cs/evol/repatch/RePatch.java` via `GitHistoryRefactoringMinerImpl.detectBetweenCommits()`
  - Timeout: 11 minutes per refactoring detection call

## Data Storage

**Databases:**
- MySQL 8.0 - Stores all pipeline evaluation results, project records, merge commits, refactorings, conflict data
  - Connection: `JDBC_URL` env var (default: `jdbc:mysql://localhost/refactoring_aware_integration_repatch?serverTimezone=UTC`)
  - Client/ORM: ActiveJDBC 2.2 (`org.javalite:activejdbc`)
  - Driver: `com.mysql.jdbc.Driver` (mysql-connector-java 8.0.16); Docker Compose sets `DB_DRIVER=com.mysql.cj.jdbc.Driver`
  - Schema: Defined in `RePatch/src/main/resources/create_integration_schema.sql`; auto-created by `DatabaseUtils.createDatabase()` if the schema does not exist
  - Database name: `refactoring_aware_integration_repatch`
  - Model classes: `Project`, `Patch`, `MergeCommit`, `MergeResult`, `ConflictingFile`, `ConflictBlock`, `Refactoring`, `RefactoringConflict`, `FileStatistics` — all in `RePatch/src/main/java/edu/unlv/cs/evol/integration/database/`
  - Schema dump available at `RePatch/database-dump/refactoring_aware_integration.sql.zip`

**File Storage:**
- Local filesystem — clone target projects are stored under `~/repatch-integration-projects/` (Docker volume: `dev_container_data_repatch:/config`)
- Merge result snapshots saved under `~/results/{projectName}/commit{id}/` (subdirs: `refMerge/`, `git/`, `refMergeResults/`, `gitResults/`)
- Temporary merge content in `~/temp/manualMerge/`
- Sample input data (project URLs + patch PR numbers) loaded from classpath resources:
  - `RePatch/src/main/resources/sample_data/repatch_integration_projects` — one project pair per line: `mainlineUrl,forkUrl`
  - `RePatch/src/main/resources/sample_data/repatch_integration_patches` — one patch per line: `mainlineUrl,projectName,prNumber,patchType`
  - Complete dataset variants in `RePatch/src/main/resources/complete_data/`

**Caching:**
- None

## Authentication & Identity

**Auth Provider:**
- GitHub OAuth Token (personal access token)
  - Implementation: Loaded from `github-oauth.properties` classpath resource in `GitHubUtils.connectToGitHub()`
  - Falls back to `GitHub.connectAnonymously()` if token is absent or file is missing
  - Placeholder token file: `RePatch/github-autho.properties` (note: filename differs from the classpath resource name `github-oauth.properties`)

## Monitoring & Observability

**Error Tracking:**
- None — errors are printed to stdout/stderr via `System.out.println()` and `e.printStackTrace()`

**Logs:**
- `java.util.logging.Logger` used in `GitHubUtils.java`
- `System.out.println()` / `System.err.println()` used extensively throughout the codebase for progress and debug output
- Project-level log file written via `Utils.log(project.getName(), message)` in `RePatch/src/main/java/edu/unlv/cs/evol/repatch/utils/Utils.java`

## CI/CD & Deployment

**Hosting:**
- IntelliJ IDEA plugin (local or Docker-based development environment)
- Docker: `lscr.io/linuxserver/webtop:ubuntu-kde` container with KDE desktop accessible via VNC/browser on ports 3000 (HTTP) and 3001 (HTTPS)
- Docker Compose file: `RePatch/docker/dev-container-repatch/docker-compose.yml`

**CI Pipeline:**
- GitHub Actions — defined in `RePatch/.github/workflows/gradle.yml`
- Triggers: push and pull_request to `master` branch
- Steps: checkout, set up JDK 11, build with `./gradlew build`
- Runs on: `ubuntu-latest`

## Environment Configuration

**Required env vars:**
- `JDBC_URL` - Full JDBC URL including database name (e.g., `jdbc:mysql://localhost/refactoring_aware_integration_repatch?serverTimezone=UTC`)
- `JDBC_URL_WITHOUT_DATABASE` - JDBC URL without database name (e.g., `jdbc:mysql://localhost?serverTimezone=UTC`)
- `JDBC_USER` - Database username
- `JDBC_PASSWORD` - Database password
- `LEFT_COMMIT` - Left branch commit SHA (used in headless `AnAction` mode)
- `RIGHT_COMMIT` - Right branch commit SHA (used in headless `AnAction` mode)
- `BASE_COMMIT` - Base/common ancestor commit SHA (used in headless `AnAction` mode)

**Secrets location:**
- GitHub OAuth token: `github-oauth.properties` on the classpath (not committed with a real value; `RePatch/github-autho.properties` contains a placeholder)
- Database credentials: environment variables (`JDBC_USER`, `JDBC_PASSWORD`) or hardcoded defaults (`root`/`root`) in `DatabaseUtils.java`

## Webhooks & Callbacks

**Incoming:**
- None — the tool operates as a batch pipeline, not a web service

**Outgoing:**
- None — all external communication is outbound API calls to GitHub REST API and Git operations against remote repositories

## Local Git Operations (JGit + git4idea)

The tool integrates deeply with two Git client libraries simultaneously:

**JGit (`org.eclipse.jgit:org.eclipse.jgit:5.10.0`):**
- Used for: `Git.open()`, `Git.cloneRepository()`, `RemoteAddCommand`, `FetchCommand`, `RevWalk`, `RevFilter`
- Files: `RePatch/src/main/java/edu/unlv/cs/evol/integration/RePatchIntegration.java`, `RePatch/src/main/java/edu/unlv/cs/evol/repatch/RePatch.java`, `RePatch/src/main/java/edu/unlv/cs/evol/integration/utils/GitUtils.java`

**git4idea (IntelliJ bundled plugin):**
- Used for: checkout, cherry-pick, reset, add, commit, history, PSI refresh operations
- Files: `RePatch/src/main/java/edu/unlv/cs/evol/integration/utils/GitUtils.java`, `RePatch/src/main/java/edu/unlv/cs/evol/repatch/utils/GitUtils.java`, `RePatch/src/main/java/edu/unlv/cs/evol/integration/RePatchIntegration.java`

---

*Integration audit: 2026-04-23*
