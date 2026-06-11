package edu.unlv.cs.evol.repatch.utils;

/**
 * The pipeline's timeout knobs, gathered out of the three hardcoded sites
 * they used to live at (Week 5A):
 *
 * <ul>
 *   <li><b>smartModeWaitMs</b> — upper bound on one smart-mode wait in
 *       {@code IntelliJ2024PlatformFacade.pumpEventsUntilSmart}; the facade
 *       logs and proceeds past it. Was {@code SMART_WAIT_DEADLINE_MS = 120_000}.</li>
 *   <li><b>refMinerDetectMs</b> — bound on the RefactoringMiner detection
 *       future in {@code RePatch.doMerge} (both sides of one scenario). Was
 *       {@code futureRefMiner.get(11, TimeUnit.MINUTES)}.</li>
 *   <li><b>scenarioBudgetMs</b> — elapsed-time budget for one merge scenario,
 *       checked after detection and after conflict detection in
 *       {@code RePatch.doMerge}. Was the two {@code 900000} checks.</li>
 * </ul>
 *
 * Defaults reproduce those values. Each is overridable per run with
 * {@code -Drepatch.timeout.smartModeWaitMs=...} /
 * {@code -Drepatch.timeout.refMinerDetectMs=...} /
 * {@code -Drepatch.timeout.scenarioBudgetMs=...} (same idiom as
 * {@code -Drepatch.log.level}); a missing, malformed, or non-positive value
 * falls back to the default.
 *
 * <p>{@code plan.tex} Week 2 envisions a typed {@code PipelineConfig} carrying
 * mode/dataPath/evaluationProject/timeouts/log level; this object is sized to
 * become its first field.
 */
public final class TimeoutPolicy {

    public static final long DEFAULT_SMART_MODE_WAIT_MS = 120_000;
    public static final long DEFAULT_REFMINER_DETECT_MS = 11 * 60_000;
    public static final long DEFAULT_SCENARIO_BUDGET_MS = 900_000;

    private final long smartModeWaitMs;
    private final long refMinerDetectMs;
    private final long scenarioBudgetMs;

    public TimeoutPolicy(long smartModeWaitMs, long refMinerDetectMs, long scenarioBudgetMs) {
        if (smartModeWaitMs <= 0 || refMinerDetectMs <= 0 || scenarioBudgetMs <= 0) {
            throw new IllegalArgumentException("timeouts must be positive: smartModeWaitMs="
                    + smartModeWaitMs + " refMinerDetectMs=" + refMinerDetectMs
                    + " scenarioBudgetMs=" + scenarioBudgetMs);
        }
        this.smartModeWaitMs = smartModeWaitMs;
        this.refMinerDetectMs = refMinerDetectMs;
        this.scenarioBudgetMs = scenarioBudgetMs;
    }

    /** The pre-policy hardcoded values: 120 s / 11 min / 15 min. */
    public static TimeoutPolicy defaults() {
        return new TimeoutPolicy(DEFAULT_SMART_MODE_WAIT_MS,
                DEFAULT_REFMINER_DETECT_MS, DEFAULT_SCENARIO_BUDGET_MS);
    }

    /** Defaults overlaid with any {@code -Drepatch.timeout.*} system properties. */
    public static TimeoutPolicy fromSystemProperties() {
        return new TimeoutPolicy(
                parseMs("repatch.timeout.smartModeWaitMs", DEFAULT_SMART_MODE_WAIT_MS),
                parseMs("repatch.timeout.refMinerDetectMs", DEFAULT_REFMINER_DETECT_MS),
                parseMs("repatch.timeout.scenarioBudgetMs", DEFAULT_SCENARIO_BUDGET_MS));
    }

    private static long parseMs(String property, long fallback) {
        String configured = System.getProperty(property);
        if (configured == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(configured.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public long smartModeWaitMs() {
        return smartModeWaitMs;
    }

    public long refMinerDetectMs() {
        return refMinerDetectMs;
    }

    public long scenarioBudgetMs() {
        return scenarioBudgetMs;
    }

    /**
     * Whether a scenario started at {@code startMs} has exceeded its budget
     * by {@code nowMs}. The pre-policy checks computed {@code start - now}
     * (always negative) so the 15-minute budget could never fire; this is the
     * intended direction.
     */
    public boolean isScenarioOverBudget(long startMs, long nowMs) {
        return nowMs - startMs > scenarioBudgetMs;
    }

    @Override
    public String toString() {
        return "TimeoutPolicy{smartModeWaitMs=" + smartModeWaitMs
                + ", refMinerDetectMs=" + refMinerDetectMs
                + ", scenarioBudgetMs=" + scenarioBudgetMs + '}';
    }
}
