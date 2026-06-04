package edu.unlv.cs.evol.repatch.platform;

/**
 * One replay or invert operation, expressed against the Week 4A shared
 * execution contract. {@link RefactoringExecutionService#execute} drives
 * the six steps; an operation implements the three it owns:
 *
 *   prepare   — steps 1–2: validate preconditions and resolve PSI targets
 *               (through {@link PsiSearchService}; throw
 *               {@link PreconditionFailed} when a target is missing,
 *               {@link UnsupportedShape} when RePatch cannot handle the
 *               refactoring's form). Resolution must happen here, not in
 *               the constructor, so the index is smart and failures are
 *               classified instead of silently returned.
 *   execute   — step 3: invoke the IntelliJ refactoring processor. Note
 *               that IntelliJ processors take their own write actions;
 *               from the EDT-dispatched headless path they are invoked
 *               directly (or via {@code invokeAndWait}, which is
 *               synchronous on the EDT), never wrapped in an outer
 *               {@code runWriteCommand}.
 *   verifyPostcondition — step 6's input: return {@code null} when the
 *               expected post-state holds, else a description of what is
 *               missing.
 *
 * Steps 4 (post-operation sync via {@link VfsSyncService}) and 5–6
 * (result capture and classification) are owned by the service so they
 * cannot be forgotten per-operation.
 */
public interface RefactoringOperation {

    /** Short human-readable identity for logs, e.g. {@code "invert MoveRenameClass a.b.C -> a.c.C"}. */
    String describe();

    /** Steps 1–2: precondition validation + PSI target resolution. */
    void prepare(RefactoringExecutionContext context) throws PreconditionFailed, UnsupportedShape;

    /** Step 3: processor invocation. Any throwable is classified PROCESSOR_THREW. */
    void execute(RefactoringExecutionContext context) throws Exception;

    /** Step 6 input: {@code null} when the post-state holds, else what is missing. */
    default String verifyPostcondition(RefactoringExecutionContext context) {
        return null;
    }

    /** Thrown from {@link #prepare} when a required PSI target or input is missing. */
    final class PreconditionFailed extends Exception {
        public PreconditionFailed(String detail) {
            super(detail);
        }
    }

    /** Thrown from {@link #prepare} when the refactoring's form is one RePatch cannot invert/replay. */
    final class UnsupportedShape extends Exception {
        public UnsupportedShape(String detail) {
            super(detail);
        }
    }
}
