# RePatch 2.0 Modernization

## What This Is

RePatch is a refactoring-aware merge tool implemented as an IntelliJ IDEA plugin. It detects
refactorings between commits using RefactoringMiner, inverts them before a Git merge, then
replays them after — reducing merge conflicts caused by refactoring operations.

This project modernizes RePatch from its 2020-era baseline to a maintainable, modern platform:
- Build system: IntelliJ Gradle Plugin 0.7.3 / IDEA 2020.1.2 → IntelliJ Platform Gradle Plugin 2.x
- Architecture: monolithic, tightly-coupled → modular with a platform adapter layer
- Reliability: silent null returns + println logging → typed results + structured logging

**Delivery model:** One branch per task (27 tasks from the spec's WBS). Each branch must
compile and build. Boss reviews and merges branches incrementally.

## Why It Exists

The current build system is entirely obsolete — the `org.jetbrains.intellij` 0.7.x Gradle plugin
uses a build model that no longer exists in the 2.x ecosystem. The plugin cannot be compiled
against any modern IntelliJ release without this migration. Additionally, the codebase mixes the
core algorithm, IDE orchestration, GitHub/Git utilities, and MySQL persistence in one module,
making debugging and extension extremely difficult.

## Who It's For

- **Developer (user):** Executing the migration task by task
- **Boss:** Reviews and approves each branch before merge — needs clean compilation on each

## Core Value

A working RePatch plugin that builds on IntelliJ Platform Gradle Plugin 2.x, with clean module
boundaries and automated verification in CI — giving the codebase a path forward for long-term
maintenance.

## Context

**Current stack:**
- IntelliJ Platform Plugin SDK (IDEA 2020.1.2, Gradle plugin 0.7.3)
- Java 11, Gradle 6.8 (wrapper)
- JGit 5.10, RefactoringMiner 2.1.0, ActiveJDBC 2.2, GitHub API
- MySQL 8.0 for evaluation persistence
- Docker dev environment (VNC-accessible)

**Existing codebase map:** `.planning/codebase/` — architecture, stack, concerns, structure docs

**Spec document:** `RePatch_2_0.pdf` — 23-page migration specification, April 2026

## Requirements

### Validated

- ✓ Core refactoring-aware merge algorithm (detect → invert → cherry-pick → replay) — existing
- ✓ IntelliJ plugin action entry point (`RePatch.java` as `AnAction`) — existing
- ✓ Headless batch evaluation pipeline (`IntegrationPipeline.java` as `ApplicationStarter`) — existing
- ✓ Conflict matrix with double-dispatch pattern (`Matrix`, `dispatcher/`, `receivers/`, `logicCells/`) — existing
- ✓ MySQL-backed evaluation persistence (ActiveJDBC models) — existing
- ✓ GitHub API integration for pull request data — existing

### Active

**Phase 0 — Baseline:**
- [ ] `P0-01`: Baseline git tag (`repatch-1.x-baseline`) created
- [ ] `P0-02`: Environment manifest documenting JDK, Gradle, IntelliJ, MySQL, GitHub auth requirements
- [ ] `P0-03`: Code hotspot inventory (all classes importing `com.intellij.*`, `git4idea.*`, impl packages)
- [ ] `P0-04`: Regression scenario list captured from sample data

**Track A — Build & CI:**
- [ ] `A1`: Gradle wrapper and Java toolchain upgraded to plugin-2.x-compatible versions
- [ ] `A2`: Legacy IntelliJ Gradle plugin replaced with `org.jetbrains.intellij.platform` 2.x
- [ ] `A3`: Dependency configs migrated (`compile`/`testCompile` → `implementation`/`testImplementation`)
- [ ] `A4`: Plugin metadata and target platform dependencies reconfigured
- [ ] `A5`: Plugin verifier and CI quality gates added
- [ ] `A6`: Local build and run workflow documented

**Track B — Startup & Runtime Entry:**
- [ ] `B1`: Action-based vs. headless execution requirements audited and decided
- [ ] `B2`: Brittle positional argument parsing replaced with typed config object
- [ ] `B3`: Project open/close lifecycle encapsulated
- [ ] `B4`: Robust exit codes and startup validation added

**Track C — Platform Compatibility Layer:**
- [ ] `C1`: `DumbServiceImpl` usage removed
- [ ] `C2`: `JavaPsiFacadeImpl` usage removed
- [ ] `C3`: Project-opening APIs isolated behind adapter layer
- [ ] `C4`: VFS/PSI synchronization logic centralized
- [ ] `C5`: Read/write action execution standardized
- [ ] `C6`: Source-root and module manipulation utilities refactored

**Track D — Refactoring Execution Modernization:**
- [ ] `D1`: Shared execution contract defined for replay operations
- [ ] `D2`: Shared execution contract defined for invert operations
- [ ] `D3`: Structured result types and failure categories added
- [ ] `D4`: Refactoring processor preconditions validated
- [ ] `D5`: Per-operation regression tests added

**Track E — Functional Improvements:**
- [ ] `E1`: Monolithic utility classes split
- [ ] `E2`: Persistence decoupled from pipeline execution
- [ ] `E3`: Logging and reporting improved
- [ ] `E4`: Deterministic timeout policies introduced
- [ ] `E5`: Unsupported-refactoring diagnostics improved
- [ ] `E6`: Module structure prepared for future core/platform/integration split

### Out of Scope

- New refactoring type support — preserve existing types, don't add new ones
- UI/UX changes to the plugin action — behavior preserved, not redesigned
- Database schema changes — persistence structure stays the same
- RefactoringMiner version upgrade — `2.1.0` stays; API surface is already a risk area

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| One branch per WBS task ID | Boss reviews and compiles each branch incrementally | Decided |
| Each branch must compile and build | Boss verifies via build artifact, not runtime testing | Decided |
| Follow spec's Phase 0→5 order | Phases are dependency-ordered; build must work before API migration | Decided |
| Phase 0 tasks included in roadmap | Baseline freeze is real work, needs tracking | Decided |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-23 after initialization*
