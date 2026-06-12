package edu.unlv.cs.evol.repatch.utils;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sink is the only bridge between the repatch layer's classified failures
 * and the integration layer's DB writer, and it is fed from multiple threads
 * (RefMiner executor, git worker threads) while drained on the EDT — so the
 * contract under test is: nothing recorded is lost, drain empties, clear
 * discards, and the phase/type derivation and 12660-artifact heuristic that
 * stamp the rows are exact.
 */
public class FailureEventSinkTest {

    @Before
    public void resetSink() {
        FailureEventSink.clear();
    }

    // ---- record / drain / clear ---------------------------------------------

    @Test
    public void drainReturnsRecordedEntriesInOrderAndEmptiesTheSink() {
        FailureEventSink.record(FailureEventSink.PHASE_DETECT, null,
                FailureEventSink.CATEGORY_DETECT_ERROR, "first");
        FailureEventSink.record(FailureEventSink.PHASE_GIT_COMMIT, null,
                FailureEventSink.CATEGORY_COMMIT_FAILED, "second");

        List<FailureEventSink.Entry> drained = FailureEventSink.drain();
        assertEquals(2, drained.size());
        assertEquals("first", drained.get(0).evidence);
        assertEquals("DETECT", drained.get(0).phase);
        assertEquals("DETECT_ERROR", drained.get(0).category);
        assertEquals("second", drained.get(1).evidence);

        assertTrue("a second drain must find nothing", FailureEventSink.drain().isEmpty());
    }

    @Test
    public void clearDiscardsEverythingRecorded() {
        FailureEventSink.record(FailureEventSink.PHASE_SCENARIO, null,
                FailureEventSink.CATEGORY_TIMEOUT, "stale entry from a wedged run");
        FailureEventSink.clear();
        assertTrue(FailureEventSink.drain().isEmpty());
    }

    @Test
    public void concurrentRecordsFromTwoThreadsAllArriveInOneDrain() throws Exception {
        final int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        Runnable producer = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < perThread; i++) {
                FailureEventSink.recordOperationFailure(
                        "invert RENAME_METHOD (m" + i + ")", "PRECONDITION_FAILED", "e" + i);
            }
        };
        Thread t1 = new Thread(producer);
        Thread t2 = new Thread(producer);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        assertEquals(2 * perThread, FailureEventSink.drain().size());
        assertTrue(FailureEventSink.drain().isEmpty());
    }

    // ---- phase / refactoring-type derivation ---------------------------------

    @Test
    public void operationFailureDerivesPhaseAndTypeFromTheDescription() {
        FailureEventSink.recordOperationFailure(
                "invert RENAME_METHOD (Rename Method public m() ...)",
                "PRECONDITION_FAILED", "[RefactoringExecution] PRECONDITION_FAILED invert RENAME_METHOD ...");
        FailureEventSink.recordOperationFailure(
                "replay MOVE_OPERATION (Move Method ...)",
                "POSTCONDITION_BROKEN", "evidence");

        List<FailureEventSink.Entry> drained = FailureEventSink.drain();
        assertEquals("INVERT", drained.get(0).phase);
        assertEquals("RENAME_METHOD", drained.get(0).refactoringType);
        assertEquals("PRECONDITION_FAILED", drained.get(0).category);
        assertEquals("REPLAY", drained.get(1).phase);
        assertEquals("MOVE_OPERATION", drained.get(1).refactoringType);
    }

    @Test
    public void unrecognizedDescriptionsFallBackInsteadOfThrowing() {
        assertEquals("UNKNOWN", FailureEventSink.phaseOf("op"));
        assertEquals("UNKNOWN", FailureEventSink.phaseOf(null));
        assertNull(FailureEventSink.refactoringTypeOf("op"));
        assertNull(FailureEventSink.refactoringTypeOf(null));
        assertNull(FailureEventSink.refactoringTypeOf("invert "));
    }

    @Test
    public void typeTokenStopsAtTheDetailParenthetical() {
        assertEquals("EXTRACT_OPERATION",
                FailureEventSink.refactoringTypeOf("invert EXTRACT_OPERATION (Extract Method ...)"));
        // No detail part at all — the whole remainder is the token.
        assertEquals("RENAME_CLASS", FailureEventSink.refactoringTypeOf("replay RENAME_CLASS"));
    }

    // ---- 12660 artifact heuristic ---------------------------------------------

    @Test
    public void artifactHeuristicMatchesOnlyUnresolvablePreconditionFailures() {
        assertTrue(FailureEventSink.isPipelineArtifact("PRECONDITION_FAILED",
                "method public m() in com.foo.Bar is not resolvable"));
        assertFalse("other categories are never artifacts",
                FailureEventSink.isPipelineArtifact("POSTCONDITION_BROKEN",
                        "method is not resolvable"));
        assertFalse("precondition failures with other causes are real",
                FailureEventSink.isPipelineArtifact("PRECONDITION_FAILED", "project is disposed"));
        assertFalse(FailureEventSink.isPipelineArtifact("PRECONDITION_FAILED", null));
    }
}
