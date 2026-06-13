package edu.unlv.cs.evol.integration;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertTrue;

/**
 * The end-of-run summary must always print — including when the verdict
 * lookup cannot reach a database (the models throw; the summary catches and
 * annotates instead of dying after a 30-minute run).
 */
public class PipelineRunResultTest {

    @Test
    public void printsSummaryWithoutDatabase() {
        PipelineRunResult result = new PipelineRunResult();
        result.record(13050, PipelineRunResult.ScenarioOutcome.EVALUATED, 1234, null);
        result.record(12592, PipelineRunResult.ScenarioOutcome.NON_CONFLICTING, 500, null);
        result.record(12660, PipelineRunResult.ScenarioOutcome.FAILED, 10,
                "IllegalStateException: boom");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(captured));
        try {
            result.printSummary();
        } finally {
            System.setOut(previous);
        }

        String summary = captured.toString();
        assertTrue(summary.contains("[PipelineSummary] PR 13050 — EVALUATED in 1s"));
        assertTrue(summary.contains("[PipelineSummary] PR 12592 — NON_CONFLICTING in 0s"));
        assertTrue(summary.contains("[PipelineSummary] PR 12660 — FAILED"));
        assertTrue(summary.contains("IllegalStateException: boom"));
        assertTrue(summary.contains("3 PRs, 1 failed"));
        assertTrue("a run with no failure events must still print the distribution line",
                summary.contains("[PipelineSummary] failures: none"));
    }

    @Test
    public void failureDistributionSortsByCountDescendingThenName() {
        PipelineRunResult result = new PipelineRunResult();
        for (int i = 0; i < 3; i++) {
            result.recordFailure("PRECONDITION_FAILED");
        }
        result.recordFailure("POSTCONDITION_BROKEN");
        result.recordFailure("COMMIT_FAILED");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(captured));
        try {
            result.printSummary();
        } finally {
            System.setOut(previous);
        }

        assertTrue(captured.toString().contains(
                "[PipelineSummary] failures: PRECONDITION_FAILED=3 COMMIT_FAILED=1 POSTCONDITION_BROKEN=1"));
    }
}
