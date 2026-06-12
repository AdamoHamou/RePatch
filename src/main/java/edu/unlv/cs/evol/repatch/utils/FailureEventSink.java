package edu.unlv.cs.evol.repatch.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Process-wide buffer carrying the pipeline's already-classified failures
 * from the {@code repatch} layer to the integration layer's database writer.
 *
 * <p>The dependency direction forces this seam: classified failures originate
 * in {@code repatch} ({@code RefactoringExecutionService}, {@code RePatch.doMerge},
 * {@code GitUtils}), but all DB access lives in
 * {@code edu.unlv.cs.evol.integration.database}, and integration depends on
 * repatch — never the reverse. So producers {@link #record} into this static
 * queue and the integration layer {@link #drain}s and persists after each
 * scenario, on the thread that owns the ActiveJDBC connection. No JDBC here.
 *
 * <p>Thread safety is load-bearing, not defensive: RefactoringMiner's
 * {@code handleException} fires on the detect executor thread and the git
 * commit classification on its own worker thread, while the drain happens on
 * the EDT.
 */
public final class FailureEventSink {

    // Phases — where in the pipeline the failure happened.
    public static final String PHASE_DETECT = "DETECT";
    public static final String PHASE_INVERT = "INVERT";
    public static final String PHASE_GIT_COMMIT = "GIT_COMMIT";
    public static final String PHASE_CHERRY_PICK = "CHERRY_PICK";
    public static final String PHASE_REPLAY = "REPLAY";
    public static final String PHASE_SCENARIO = "SCENARIO";

    // Categories beyond RefactoringExecutionResult.Status (whose names are
    // recorded verbatim for operation failures).
    public static final String CATEGORY_TIMEOUT = "TIMEOUT";
    public static final String CATEGORY_DETECT_ERROR = "DETECT_ERROR";
    public static final String CATEGORY_COMMIT_FAILED = "COMMIT_FAILED";
    public static final String CATEGORY_SCENARIO_ABORTED = "SCENARIO_ABORTED";

    /** One classified failure, as recorded at its source. Immutable. */
    public static final class Entry {
        public final String phase;
        public final String refactoringType;
        public final String category;
        public final String evidence;

        Entry(String phase, String refactoringType, String category, String evidence) {
            this.phase = phase;
            this.refactoringType = refactoringType;
            this.category = category;
            this.evidence = evidence;
        }
    }

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();

    private FailureEventSink() {
    }

    /** Record a classified failure. Callable from any thread; never throws. */
    public static void record(String phase, String refactoringType, String category, String evidence) {
        QUEUE.add(new Entry(phase, refactoringType, category, evidence));
    }

    /**
     * Record an invert/replay operation failure, deriving phase and
     * refactoring type from the operation description (the dispatchers and
     * migrated operations all describe themselves as
     * {@code "invert RENAME_METHOD (...)"} / {@code "replay MOVE_OPERATION (...)"}).
     */
    public static void recordOperationFailure(String operationDescription, String category, String evidence) {
        record(phaseOf(operationDescription), refactoringTypeOf(operationDescription), category, evidence);
    }

    /** Remove and return everything recorded so far, oldest first. */
    public static List<Entry> drain() {
        List<Entry> drained = new ArrayList<>();
        Entry entry;
        while ((entry = QUEUE.poll()) != null) {
            drained.add(entry);
        }
        return drained;
    }

    /** Discard everything recorded so far (startup hygiene). */
    public static void clear() {
        QUEUE.clear();
    }

    /**
     * Whether a failure is attributable to the known 12660 content-registration
     * tool bug rather than the subject code: its signature is a precondition
     * failure whose evidence says the PSI target is "not resolvable"
     * (see repatch-migration-known-issues.md §1).
     */
    public static boolean isPipelineArtifact(String category, String evidence) {
        return "PRECONDITION_FAILED".equals(category)
                && evidence != null && evidence.contains("not resolvable");
    }

    /**
     * INVERT or REPLAY from the operation description's flow prefix;
     * "UNKNOWN" for a description carrying neither (does not occur in the
     * pipeline — defensive only).
     */
    static String phaseOf(String operationDescription) {
        if (operationDescription != null) {
            if (operationDescription.startsWith("invert ")) {
                return PHASE_INVERT;
            }
            if (operationDescription.startsWith("replay ")) {
                return PHASE_REPLAY;
            }
        }
        return "UNKNOWN";
    }

    /**
     * The refactoring type token after the flow prefix, e.g.
     * {@code "invert RENAME_METHOD (detail)"} → {@code "RENAME_METHOD"};
     * null when the description has no recognizable prefix or no token.
     */
    static String refactoringTypeOf(String operationDescription) {
        if (operationDescription == null) {
            return null;
        }
        String rest;
        if (operationDescription.startsWith("invert ")) {
            rest = operationDescription.substring("invert ".length());
        } else if (operationDescription.startsWith("replay ")) {
            rest = operationDescription.substring("replay ".length());
        } else {
            return null;
        }
        int end = rest.indexOf(' ');
        String token = (end >= 0 ? rest.substring(0, end) : rest).trim();
        return token.isEmpty() ? null : token;
    }
}
