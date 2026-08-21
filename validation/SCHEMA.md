# Validation dataset schema

One JSON record per benchmark case, one record per line (`dataset.jsonl`).
Produced by `repatch validate` in the evaluation container; consumed by
`validation/analyze.py`. Stage outcomes are kept as separate fields and are
never collapsed — the `validation.final_outcome` enum is derived, and the
detailed fields always retain the underlying reason.

## Identity and provenance

| Field | Meaning |
|---|---|
| `case_id` | Stable identifier: `<Owner>-<RepoName>__pr<number>` of the **target** fork + the mainline PR. Reusable for the later SALP–RCAP–SVRP comparison. |
| `source_repo` / `target_repo` | GitHub URLs; the patch originates in `source_repo` (mainline) and is integrated into `target_repo` (the variant fork). |
| `pr_number`, `patch_type` | The mainline pull request and its dataset label. |
| `source_revision` | The PR's merge commit (the change being transferred). |
| `target_revision` | The target fork revision **before** integration (= the baseline state). |
| `base_revision` | Parent of the merge commit (cherry-pick base). |
| `provenance.run_db` | The pipeline run database the level-1 outcome came from. |
| `provenance.results_dir` | Per-case artifact directory (evidence trees, state bundle, logs). |

## Level 1 — integration (`integration`)

| Field | Meaning |
|---|---|
| `status` | `CONFLICT_FREE` (RePatch produced a conflict-free target state) · `CONFLICTED` (RePatch reports conflicts) · `GIT_CLEAN` (plain git cherry-pick applies cleanly; the refactoring engine is not exercised) · `TOOL_TIMEOUT` (RefactoringMiner/RePatch timeout, no result) · `SKIPPED_NO_SCENARIO` (PR metadata unavailable — deleted/unmerged PR) · `NOT_RUN` |
| `repatch_verdict` / `git_verdict` | `{files, conflicts, loc, runtime_ms}` — conflicting-file/-block/-LOC counts for RePatch and for the plain git cherry-pick control. |
| `runtime_ms` | RePatch integration runtime. |
| `result_sha` | Commit of the exact RePatch-produced target state. |
| `bundle` | Git bundle holding that state (`target_revision..result_sha`); any clone containing `target_revision` reproduces the state with `git fetch <bundle>` + checkout. |

`CONFLICT_FREE` and `GIT_CLEAN` both mean level 1 = yes; they are kept
distinct because only the former exercises the refactoring engine.

## Levels 2–3 — build and test (`baseline`, `post`)

Both sides have the same shape; `baseline` is the target at
`target_revision`, `post` is the RePatch-produced state. Both are built and
tested identically (same JDK, same test scope).

| Field | Meaning |
|---|---|
| `build` | `PASS` · `FAIL` · `TIMEOUT` · `UNSUPPORTED` |
| `build_system`, `jdk` | Auto-detected build tool and the JDK used. |
| `tests` | `DONE` (suite executed; failures are data, not errors) · `ERROR` (harness could not execute tests) · `TIMEOUT` · `SKIPPED` |
| `tests_total`, `failing_tests` | Count of executed test cases and the identities (`Class#method`) of failing ones, parsed from JUnit XML. |
| `post.newly_failing_tests` | `post.failing_tests − baseline.failing_tests` — the only failures attributable to RePatch. A test already failing at baseline is never counted as a regression. |
| `inconclusive_cause` | Set instead of the above when infrastructure prevented the stage (`missing_result_state`, `no_provisioned_clone`, `checkout_failed`, ...). |
| `cached` | `true` on `baseline` when reused from the per-project baseline cache (every case of a project shares its `target_revision`). |

## Final classification (`validation`)

| Field | Meaning |
|---|---|
| `final_outcome` | `VALID` · `INTEGRATION_FAIL` · `BUILD_FAIL` · `TEST_FAIL` · `INCONCLUSIVE` — mutually exclusive, per the study definitions; plus `PENDING` for cases whose build/test stages have not run yet. |
| `failure_stage` | `integration` · `build` · `test` · `infrastructure` · `null` |
| `inconclusive_cause` | Machine-readable cause (`tool_timeout`, `baseline_build_fail`, `pr_metadata_unavailable`, `test_harness_error`, ...). |
| `test_scope` | `full`, `module:<m1,m2,...>` (gradle multi-module projects: only modules touched by the change, applied identically to baseline and post), or `none`. |

Derivation rules: `CONFLICTED → INTEGRATION_FAIL`; conflict-free with a
failing post build (and passing baseline build) `→ BUILD_FAIL`; buildable
with non-empty `newly_failing_tests` `→ TEST_FAIL`; buildable with none
`→ VALID`; anything whose baseline or infrastructure prevents a defensible
comparison (baseline does not build, tool timeout, missing PR metadata,
harness errors) `→ INCONCLUSIVE` with the cause recorded.
