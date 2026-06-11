package edu.unlv.cs.evol.repatch.utils;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The {@link TimeoutPolicy} defaults must reproduce the three pre-policy
 * hardcoded values exactly (120 s smart-mode wait, 11 min RefMiner detect,
 * 15 min scenario budget) — those are the values every passing pipeline run
 * to date has used. The property overlay and the over-budget direction
 * (now - start, not the historical inverted start - now) are the rest of the
 * contract.
 */
public class TimeoutPolicyTest {

    private static final String SMART = "repatch.timeout.smartModeWaitMs";
    private static final String REFMINER = "repatch.timeout.refMinerDetectMs";
    private static final String SCENARIO = "repatch.timeout.scenarioBudgetMs";

    @After
    public void clearProperties() {
        System.clearProperty(SMART);
        System.clearProperty(REFMINER);
        System.clearProperty(SCENARIO);
    }

    // ---- defaults ------------------------------------------------------------

    @Test
    public void defaultsReproduceThePrePolicyHardcodedValues() {
        TimeoutPolicy policy = TimeoutPolicy.defaults();
        assertEquals(120_000, policy.smartModeWaitMs());
        assertEquals(11 * 60_000, policy.refMinerDetectMs());
        assertEquals(900_000, policy.scenarioBudgetMs());
    }

    @Test
    public void fromSystemPropertiesWithNothingSetMatchesDefaults() {
        TimeoutPolicy policy = TimeoutPolicy.fromSystemProperties();
        assertEquals(TimeoutPolicy.defaults().smartModeWaitMs(), policy.smartModeWaitMs());
        assertEquals(TimeoutPolicy.defaults().refMinerDetectMs(), policy.refMinerDetectMs());
        assertEquals(TimeoutPolicy.defaults().scenarioBudgetMs(), policy.scenarioBudgetMs());
    }

    // ---- property overlay ------------------------------------------------------

    @Test
    public void propertiesOverrideIndividualTimeoutsLeavingOthersAtDefault() {
        System.setProperty(SMART, "30000");
        System.setProperty(SCENARIO, " 1200000 ");
        TimeoutPolicy policy = TimeoutPolicy.fromSystemProperties();
        assertEquals(30_000, policy.smartModeWaitMs());
        assertEquals(TimeoutPolicy.DEFAULT_REFMINER_DETECT_MS, policy.refMinerDetectMs());
        assertEquals(1_200_000, policy.scenarioBudgetMs());
    }

    @Test
    public void malformedOrNonPositivePropertyFallsBackToDefault() {
        System.setProperty(SMART, "two minutes");
        System.setProperty(REFMINER, "0");
        System.setProperty(SCENARIO, "-5");
        TimeoutPolicy policy = TimeoutPolicy.fromSystemProperties();
        assertEquals(TimeoutPolicy.DEFAULT_SMART_MODE_WAIT_MS, policy.smartModeWaitMs());
        assertEquals(TimeoutPolicy.DEFAULT_REFMINER_DETECT_MS, policy.refMinerDetectMs());
        assertEquals(TimeoutPolicy.DEFAULT_SCENARIO_BUDGET_MS, policy.scenarioBudgetMs());
    }

    // ---- construction ----------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNonPositiveTimeouts() {
        new TimeoutPolicy(0, 1, 1);
    }

    // ---- scenario budget ---------------------------------------------------------

    @Test
    public void scenarioIsOverBudgetOnlyWhenElapsedExceedsTheBudget() {
        TimeoutPolicy policy = new TimeoutPolicy(1, 1, 900_000);
        long start = 1_000_000;
        assertFalse(policy.isScenarioOverBudget(start, start));
        assertFalse(policy.isScenarioOverBudget(start, start + 900_000));
        assertTrue(policy.isScenarioOverBudget(start, start + 900_001));
    }

    @Test
    public void overBudgetUsesElapsedTimeNotTheHistoricalInvertedDifference() {
        // The pre-policy checks computed start - now (always negative), so a
        // 16-minute scenario was never flagged. It must be now.
        TimeoutPolicy policy = TimeoutPolicy.defaults();
        long start = 5_000_000;
        assertTrue(policy.isScenarioOverBudget(start, start + 16 * 60_000));
    }
}
