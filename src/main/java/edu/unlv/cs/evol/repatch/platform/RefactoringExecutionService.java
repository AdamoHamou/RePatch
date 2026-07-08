package edu.unlv.cs.evol.repatch.platform;

import edu.unlv.cs.evol.repatch.utils.FailureEventSink;

/**
 * Drives the Week 4A six-step execution contract for every replay/invert
 * operation:
 *
 *   1. precondition validation (project alive, dumb tasks drained, then the
 *      operation's own checks);
 *   2. PSI target resolution (inside the operation's {@code prepare}, via
 *      {@link PsiSearchService});
 *   3. processor invocation in the correct context ({@code execute});
 *   4. post-operation synchronization ({@link VfsSyncService#commitAndReparse});
 *   5. structured result capture ({@link RefactoringExecutionResult});
 *   6. failure classification (precondition-failed / processor-threw /
 *      postcondition-broken / unsupported-shape).
 *
 * The service owns steps 4–6 so no operation can forget them; before this
 * existed each of the 23 operation classes hand-rolled its own threading
 * and error handling, and most failures were silent {@code return}s.
 */
public final class RefactoringExecutionService {

    /**
     * REPLICATION TOGGLE — bug-for-bug parity with the UNFIXED 1.x engine.
     *
     * The unfixed 1.x dispatchers wrapped every invert/replay op in
     * {@code catch (Exception)}, which does NOT catch {@link Error}. When an
     * IntelliJ move/rename-class processor threw an {@link AssertionError}
     * (an Error, not an Exception), it escaped 1.x's guard and propagated out
     * of {@code doMerge} — ABORTING the whole scenario. The integration
     * harness catches that Error in {@code runRefMerge}
     * ({@code catch (AssertionError | OutOfMemoryError | ...)}), leaves the
     * returned conflict list empty, and derives the verdict from the RESIDUAL
     * working tree (Utils.saveContent -> extractMergeConflicts). Because the
     * abort happens mid-invert, before the cherry-pick that would introduce
     * conflict markers, that residual tree is frequently marker-free -> the
     * vacuous 0/0/0 "wins".
     *
     * The 2.0 migration replaced that with guard-and-continue: it catches
     * {@code Throwable} here, classifies the AssertionError as PROCESSOR_THREW,
     * skips just that op, and continues to a "corrected" real conflict count.
     * That is why 2.0 reports conflicts where 1.x reported 0/0/0.
     *
     * When this flag is true we re-throw {@link Error} instead of swallowing
     * it, restoring 1.x's abort disposition so 2.0 REPRODUCES 1.x's verdicts
     * rather than correcting them. Set false to get 2.0's normal
     * guard-and-continue behavior back.
     */
    private static final boolean REPLICATE_1X_ABORT_ON_ERROR = true;

    private final RefactoringExecutionContext context;

    public RefactoringExecutionService(RefactoringExecutionContext context) {
        this.context = context;
    }

    public RefactoringExecutionContext getContext() {
        return context;
    }

    /**
     * Single choke point for ALL operation results: every classified failure
     * is recorded into the {@link FailureEventSink} here, so the 23 operation
     * classes need no instrumentation and none can be forgotten.
     */
    public RefactoringExecutionResult execute(RefactoringOperation operation) {
        RefactoringExecutionResult result = doExecute(operation);
        if (!result.isSuccess()) {
            FailureEventSink.recordOperationFailure(result.getOperation(),
                    result.getStatus().name(), result.describe());
        }
        return result;
    }

    private RefactoringExecutionResult doExecute(RefactoringOperation operation) {
        String description = operation.describe();

        // EXPERIMENT (repro-mech, 2026-07-04): force component-1 abort to
        // replicate 1.x's "AssertionError-during-invert" mechanism. In 1.x a
        // move/rename-class processor threw an AssertionError (an Error, not an
        // Exception) that escaped doMerge's per-op catch and aborted the whole
        // merge BEFORE cherryPick (RePatch.doMerge line ~205). Because no
        // cherry-pick ran, the working tree was left at the plain checked-out
        // variant commit with NO conflict markers; the harness then SAVES that
        // residual tree and computes the verdict by SCANNING it for markers
        // (RePatchIntegration.extractMergeConflicts), so a marker-free tree
        // reads as 0/0/0. 2.0's 2024.3 processors NEVER throw that Error (they
        // reclassify as typed guard Exceptions -> 0 PROCESSOR_THREW), so the
        // abort never fires naturally. Here we inject it, only DURING INVERT
        // (so the abort is always before cherry-pick, keeping the tree clean
        // through 2.0's OWN pipeline -- no artificial git reset).
        // Modes (-Drepatch.forceAbort=): "shape" throws on the first
        // class-level move/rename op (1.x's exact AssertionError shape);
        // "first" throws on the very first invert op (unconditional).
        String forceAbort = System.getProperty("repatch.forceAbort", "");
        if (!forceAbort.isEmpty() && description.startsWith("invert")) {
            boolean isClassOp = description.contains("RENAME_CLASS")
                    || description.contains("MOVE_CLASS")
                    || description.contains("MOVE_RENAME_CLASS");
            if (forceAbort.equals("first") || (forceAbort.equals("shape") && isClassOp)) {
                System.out.println("[FORCE-ABORT:" + forceAbort + "] aborting merge before cherry-pick at: "
                        + description);
                throw new AssertionError("[FORCE-ABORT:" + forceAbort + "] simulated 1.x move/rename-class "
                        + "processor AssertionError to abort the merge before cherry-pick: " + description);
            }
        }

        // Step 1 (shared half): project liveness + index readiness.
        if (context.getProject().isDisposed()) {
            return RefactoringExecutionResult.preconditionFailed(description, "project is disposed");
        }
        context.getIndexing().drainDumbTasks(context.getProject());

        // Steps 1 (operation half) + 2: validation and PSI resolution.
        try {
            operation.prepare(context);
        } catch (RefactoringOperation.PreconditionFailed e) {
            return RefactoringExecutionResult.preconditionFailed(description, e.getMessage());
        } catch (RefactoringOperation.UnsupportedShape e) {
            return RefactoringExecutionResult.unsupportedShape(description, e.getMessage());
        } catch (Throwable t) {
            // Replicate 1.x: let Error (e.g. AssertionError from a platform
            // processor) propagate out to abort the scenario; only classify
            // genuine Exceptions as guarded failures.
            if (REPLICATE_1X_ABORT_ON_ERROR && t instanceof Error) {
                throw (Error) t;
            }
            return RefactoringExecutionResult.processorThrew(description, t);
        }

        // Step 3: processor invocation.
        try {
            operation.execute(context);
        } catch (Throwable t) {
            // Replicate 1.x: let Error (e.g. AssertionError from a platform
            // processor) propagate out to abort the scenario; only classify
            // genuine Exceptions as guarded failures.
            if (REPLICATE_1X_ABORT_ON_ERROR && t instanceof Error) {
                throw (Error) t;
            }
            return RefactoringExecutionResult.processorThrew(description, t);
        }

        // Step 4: post-operation synchronization, so the next operation's
        // PSI reads observe this one's effects.
        context.getVfs().commitAndReparse(context.getProject());

        // Steps 5–6: postcondition check and classified capture.
        String broken = operation.verifyPostcondition(context);
        if (broken != null) {
            return RefactoringExecutionResult.postconditionBroken(description, broken);
        }
        return RefactoringExecutionResult.success(description);
    }
}
