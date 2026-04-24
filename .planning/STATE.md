# RePatch 2.0 Modernization — State

**Last updated:** 2026-04-23

## Project Reference

- **Core value:** A working RePatch plugin that builds on IntelliJ Platform Gradle Plugin 2.x, with clean module boundaries and automated verification in CI.
- **Delivery model:** One Git branch per WBS task, each compiling + building cleanly for boss review.
- **Current focus:** Milestone 1 (Build Migration Ready) — start with baseline freeze before any code changes.

## Current Position

- **Milestone:** M1 — Build Migration Ready
- **Phase:** Phase 1 (P0-01 Baseline Tag)
- **Plan:** Not yet planned
- **Status:** Ready to start
- **Progress:** 0 / 31 phases complete (0%)
- **Progress bar:** `[..............................]`

## Next Action

Run `/gsd-plan-phase 1` to generate the execution plan for Phase 1 (P0-01 Baseline Tag).

## Milestone Progress

| Milestone | Phases | Status |
|-----------|--------|--------|
| M1 — Build Migration Ready | 1–10 | Active, not started |
| M2 — Runtime Compatibility Ready | 11–14 | Pending |
| M3 — Refactoring Engine Stabilized | 15–25 | Pending |
| M4 — RePatch 2.0 Candidate | 26–31 | Pending |

## Performance Metrics

- Phases completed: 0
- Phases in progress: 0
- Branches merged: 0
- Last CI run: n/a
- Last `verifyPlugin` report: not yet generated (Phase 9 deliverable)

## Accumulated Context

### Key Decisions

| Decision | Phase | Rationale |
|----------|-------|-----------|
| Sequential integer phase numbering (1–31) | Init | GSD framework expects integer phases; track/task IDs preserved as WBS labels. |
| One branch per phase | Init | Boss reviews each branch incrementally; matches spec delivery model. |
| Each branch must compile + build | Init | Boss verifies via build artifact, not runtime behavior. |
| Phase 0 tasks included as real phases | Init | Baseline freeze is real work and needs tracking. |
| Target `IC-2024.2.4` first | Research | Recommended by platform 2.x migration guide; later bumps out of scope. |

### Open Todos

- None (roadmap initialization complete).

### Blockers

- None.

### Risks to Watch

- **git4idea internal APIs** (Tracks B3, C4, D1, D2) — `GitCherryPicker`, `GitBrancher`, `GitMerger` marked `@ApiStatus.Internal`; fallback is `Git.getInstance().runCommand(GitLineHandler)`.
- **ActiveJDBC Gradle plugin 1.2 + Gradle 8.10 compatibility** (Phase 5, A1) — unknown compatibility; spike required.
- **Runtime-only failures** (Track C) — some issues like `VirtualFileManager.syncRefresh()` crash at runtime on 2022.x+ and will not surface in build-only checks; plan static audits accordingly.

## Session Continuity

- Spec document: `RePatch_2_0.pdf` (23 pages, April 2026).
- Codebase docs: `.planning/codebase/`.
- Research: `.planning/research/SUMMARY.md` (+ `STACK.md`, `ARCHITECTURE.md`, `FEATURES.md`, `PITFALLS.md`).
- Roadmap: `.planning/ROADMAP.md`.
- Requirements: `.planning/REQUIREMENTS.md`.
- Config: `.planning/config.json` (mode=yolo, granularity=fine, parallelization=true).

On resume: check `Current Position`, then run `/gsd-plan-phase <N>` for the active phase.
