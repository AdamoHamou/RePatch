package edu.unlv.cs.evol.integration;

import org.junit.Test;
import org.kohsuke.github.GHFileNotFoundException;

import java.io.IOException;
import java.net.ConnectException;

import static edu.unlv.cs.evol.integration.RePatchIntegration.PR_FETCH_MAX_ATTEMPTS;
import static edu.unlv.cs.evol.integration.RePatchIntegration.PrFetchFailure;
import static edu.unlv.cs.evol.integration.RePatchIntegration.classifyPrFetchFailure;
import static org.junit.Assert.assertEquals;

/*
 * B4/SPEC-9: PR-fetch failure classification. A 404 is a permanent fact
 * about the PR; transient network failures must be retried and, at the cap,
 * given up WITHOUT the sticky done-flag (a Connection-refused blip once got
 * a valid patch recorded as "not found").
 */
public class PrFetchFailureTest {

    @Test
    public void notFoundIsPermanentOnAnyAttempt() {
        IOException notFound = new GHFileNotFoundException("404");
        assertEquals(PrFetchFailure.PERMANENT_NOT_FOUND,
                classifyPrFetchFailure(notFound, 1, PR_FETCH_MAX_ATTEMPTS));
        assertEquals(PrFetchFailure.PERMANENT_NOT_FOUND,
                classifyPrFetchFailure(notFound, PR_FETCH_MAX_ATTEMPTS, PR_FETCH_MAX_ATTEMPTS));
    }

    @Test
    public void transientFailureRetriesBelowCap() {
        assertEquals(PrFetchFailure.RETRY,
                classifyPrFetchFailure(new ConnectException("refused"), 1, PR_FETCH_MAX_ATTEMPTS));
        assertEquals(PrFetchFailure.RETRY,
                classifyPrFetchFailure(new IOException("reset"), PR_FETCH_MAX_ATTEMPTS - 1, PR_FETCH_MAX_ATTEMPTS));
    }

    @Test
    public void transientFailureGivesUpAtCap() {
        assertEquals(PrFetchFailure.GIVE_UP,
                classifyPrFetchFailure(new ConnectException("refused"), PR_FETCH_MAX_ATTEMPTS, PR_FETCH_MAX_ATTEMPTS));
    }
}
