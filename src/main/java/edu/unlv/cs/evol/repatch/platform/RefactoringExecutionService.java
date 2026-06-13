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
            return RefactoringExecutionResult.processorThrew(description, t);
        }

        // Step 3: processor invocation.
        try {
            operation.execute(context);
        } catch (Throwable t) {
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
