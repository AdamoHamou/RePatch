# RePatch 2.0 Modernization

## Project

Modernizing RePatch from IntelliJ Gradle Plugin 0.7.3/IDEA 2020.1.2 to IntelliJ Platform Gradle Plugin 2.x.
31 atomic phases, one Git branch per phase. Each branch must compile and build cleanly for boss review.

## GSD Workflow

- Planning docs: `.planning/`
- Current state: `.planning/STATE.md`
- Roadmap: `.planning/ROADMAP.md`
- Requirements: `.planning/REQUIREMENTS.md`
- Research: `.planning/research/`

## Branch Naming Convention

`task/<wbs-id>-<short-description>` — e.g. `task/a1-gradle-upgrade`, `task/c1-dumbservice-impl-removal`

## Key Constraints

- **Each branch must compile and build** — boss verifies via `./gradlew build`
- **One task per branch** — no bundling multiple WBS tasks
- **Follow spec phase order** — phases have dependencies; P0 before A before B before C/D before E
- **No new refactoring types** — preserve existing behavior, don't add capabilities
- **No database schema changes**

## Milestone Gates

| Milestone | Phases | Gate |
|-----------|--------|------|
| M1 — Build Migration Ready | 1–10 (P0 + Track A) | Builds on IntelliJ Platform Gradle Plugin 2.x; `verifyPlugin` in CI |
| M2 — Runtime Compatibility Ready | 11–14 (Track B) | Headless pipeline boots with typed config |
| M3 — Refactoring Engine Stabilized | 15–25 (Tracks C + D) | No `*.impl.*` imports; shared replay/invert contracts; regression tests |
| M4 — RePatch 2.0 Candidate | 26–31 (Track E) | Utils split, persistence decoupled, structured logging, module boundaries |

## Starting a Phase

```
/gsd-plan-phase <N>
```

Then execute the plan. Create a branch named per the convention above.

## Research Findings (critical)

- `git4idea` cherry-pick/merge/checkout APIs have moved toward `@ApiStatus.Internal` — use `Git.getInstance().runCommand(GitLineHandler)` instead
- `VirtualFileManager.syncRefresh()` **throws at runtime** (not compile time) when called inside a read action on 2022.x+ — current RePatch code will hit this
- `ServiceManager.getService(...)` was removed in 2023.1 — won't compile
- ActiveJDBC Gradle plugin 1.2 + Gradle 8.10 compatibility is **unknown** — spike required in Phase 5 (A1)
- First `verifyPlugin` run will surface dozens of errors — that report IS Track C's to-do list
