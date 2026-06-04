package edu.unlv.cs.evol.repatch.platform;

/**
 * Typed outcome of one replay/invert operation executed through
 * {@link RefactoringExecutionService} (Week 4 / Workstream 4A, step 5).
 *
 * The status taxonomy is the contract's step 6 — failure classification.
 * Before this existed, the dispatchers counted only thrown exceptions and
 * the operation classes' silent {@code return}s (unresolvable PSI targets,
 * unsupported shapes) were indistinguishable from successes; the
 * "{@code N refactorings were not inverted}" log line carried no cause.
 */
public final class RefactoringExecutionResult {

    public enum Status {
        /** The operation ran and its postconditions held. */
        SUCCESS,
        /** Step 1/2 failed: missing PSI target, disposed project, bad input. */
        PRECONDITION_FAILED,
        /** Step 3 failed: the IntelliJ processor (or operation body) threw. */
        PROCESSOR_THREW,
        /** Step 6 failed: execution finished but the expected state is absent. */
        POSTCONDITION_BROKEN,
        /** The refactoring's shape is one RePatch does not support inverting/replaying. */
        UNSUPPORTED_SHAPE
    }

    private final Status status;
    private final String operation;
    private final String detail;
    private final Throwable failure;

    private RefactoringExecutionResult(Status status, String operation, String detail, Throwable failure) {
        this.status = status;
        this.operation = operation;
        this.detail = detail;
        this.failure = failure;
    }

    public static RefactoringExecutionResult success(String operation) {
        return new RefactoringExecutionResult(Status.SUCCESS, operation, null, null);
    }

    public static RefactoringExecutionResult preconditionFailed(String operation, String detail) {
        return new RefactoringExecutionResult(Status.PRECONDITION_FAILED, operation, detail, null);
    }

    public static RefactoringExecutionResult processorThrew(String operation, Throwable failure) {
        return new RefactoringExecutionResult(Status.PROCESSOR_THREW, operation,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure);
    }

    public static RefactoringExecutionResult postconditionBroken(String operation, String detail) {
        return new RefactoringExecutionResult(Status.POSTCONDITION_BROKEN, operation, detail, null);
    }

    public static RefactoringExecutionResult unsupportedShape(String operation, String detail) {
        return new RefactoringExecutionResult(Status.UNSUPPORTED_SHAPE, operation, detail, null);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public String getOperation() {
        return operation;
    }

    public String getDetail() {
        return detail;
    }

    public Throwable getFailure() {
        return failure;
    }

    /** One-line classified summary for the run log. */
    public String describe() {
        StringBuilder sb = new StringBuilder("[RefactoringExecution] ")
                .append(status).append(' ').append(operation);
        if (detail != null) {
            sb.append(" — ").append(detail);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
