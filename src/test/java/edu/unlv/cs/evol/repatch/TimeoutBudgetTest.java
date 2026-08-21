package edu.unlv.cs.evol.repatch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/*
 * SPEC-7: the wall-clock budget arithmetic. The historical bug was reversed
 * operands (start - now), always negative, which made both mid-pipeline
 * timeout checks dead code — encoded here as the regression case.
 */
public class TimeoutBudgetTest {

    @Test
    public void withinBudgetDoesNotTimeOut() {
        assertFalse(RePatch.budgetExceeded(1_000L, 1_000L + 900_000L, 900_000L));
    }

    @Test
    public void overBudgetTimesOut() {
        assertTrue(RePatch.budgetExceeded(1_000L, 1_000L + 900_001L, 900_000L));
    }

    @Test
    public void reversedOperandsRegression() {
        // The pre-fix expression was (start - now) > budget: with now far past
        // the deadline that is a large NEGATIVE number, i.e. never a timeout.
        long start = 1_000L;
        long now = start + 10 * 900_000L;
        assertTrue(RePatch.budgetExceeded(start, now, 900_000L));
        assertFalse((start - now) > 900_000L);
    }
}
