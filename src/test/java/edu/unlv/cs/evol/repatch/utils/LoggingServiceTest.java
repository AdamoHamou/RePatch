package edu.unlv.cs.evol.repatch.utils;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The level threshold, line format, and per-scenario operation-id stamping of
 * the {@link LoggingService} carved out of the two {@code Utils} classes. The
 * format is asserted through the timestamp-free {@code format} helper so it
 * stays deterministic; the one emission test proves the level, op id, and the
 * caller's greppable prefix all survive into the real output line.
 */
public class LoggingServiceTest {

    @After
    public void restore() {
        LoggingService.setThreshold(LoggingService.Level.INFO);
        LoggingService.clearOperationContext();
        System.setOut(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out)));
    }

    // ---- format ------------------------------------------------------------

    @Test
    public void formatPrependsLevelAndOperationIdKeepingMessageVerbatim() {
        assertEquals("INFO [PR-13050] [GitPipeline] COMMIT_NOOP",
                LoggingService.format(LoggingService.Level.INFO, "PR-13050", "[GitPipeline] COMMIT_NOOP"));
    }

    @Test
    public void formatOmitsBracketsWhenOperationIdAbsentOrBlank() {
        assertEquals("WARN preflight skipped",
                LoggingService.format(LoggingService.Level.WARN, null, "preflight skipped"));
        assertEquals("WARN preflight skipped",
                LoggingService.format(LoggingService.Level.WARN, "   ", "preflight skipped"));
    }

    @Test
    public void formatKeepsExistingPrefixesGreppable() {
        for (String prefix : new String[] {"[GitPipeline]", "[RefactoringExecution]", "[PipelineSummary]"}) {
            String line = LoggingService.format(LoggingService.Level.ERROR, "PR-1", prefix + " detail");
            assertTrue("must still contain " + prefix, line.contains(prefix));
        }
    }

    // ---- message rendering -------------------------------------------------

    @Test
    public void renderMessageFlattensThrowableToMessagePlusStackTrace() {
        Throwable t = new IllegalStateException("boom");
        String rendered = LoggingService.renderMessage(t);
        assertTrue(rendered.startsWith("boom\n"));
        assertTrue(rendered.contains("LoggingServiceTest"));
    }

    @Test
    public void renderMessagePassesStringsThroughAndStringifiesOthers() {
        assertEquals("hello", LoggingService.renderMessage("hello"));
        assertEquals("42", LoggingService.renderMessage(42));
    }

    // ---- threshold ---------------------------------------------------------

    @Test
    public void defaultThresholdSuppressesDebugOnly() {
        LoggingService.setThreshold(LoggingService.Level.INFO);
        assertFalse(LoggingService.isEnabled(LoggingService.Level.DEBUG));
        assertTrue(LoggingService.isEnabled(LoggingService.Level.INFO));
        assertTrue(LoggingService.isEnabled(LoggingService.Level.WARN));
        assertTrue(LoggingService.isEnabled(LoggingService.Level.ERROR));
    }

    @Test
    public void raisingThresholdToErrorSuppressesEverythingBelow() {
        LoggingService.setThreshold(LoggingService.Level.ERROR);
        assertFalse(LoggingService.isEnabled(LoggingService.Level.DEBUG));
        assertFalse(LoggingService.isEnabled(LoggingService.Level.INFO));
        assertFalse(LoggingService.isEnabled(LoggingService.Level.WARN));
        assertTrue(LoggingService.isEnabled(LoggingService.Level.ERROR));
    }

    @Test
    public void loweringThresholdToDebugEnablesDebug() {
        LoggingService.setThreshold(LoggingService.Level.DEBUG);
        assertTrue(LoggingService.isEnabled(LoggingService.Level.DEBUG));
    }

    // ---- operation context -------------------------------------------------

    @Test
    public void operationContextIsSetAndCleared() {
        assertNull(LoggingService.currentOperationId());
        LoggingService.setOperationContext("PR-12660");
        assertEquals("PR-12660", LoggingService.currentOperationId());
        LoggingService.clearOperationContext();
        assertNull(LoggingService.currentOperationId());
    }

    @Test
    public void emittedLineCarriesLevelContextOpIdAndGreppablePrefix() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));

        LoggingService.setOperationContext("PR-13050");
        // No op id bound to the instance — it must fall back to thread context.
        LoggingService.forProject(null).warn("[GitPipeline] COMMIT_NOOP — nothing to commit");

        String line = captured.toString();
        assertTrue("level present", line.contains("WARN"));
        assertTrue("context op id present", line.contains("[PR-13050]"));
        assertTrue("greppable prefix present", line.contains("[GitPipeline] COMMIT_NOOP"));
    }

    @Test
    public void debugIsNotEmittedUnderDefaultThreshold() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));

        LoggingService.forProject(null).debug("[GitPipeline] should not appear");

        assertEquals("", captured.toString());
    }
}
