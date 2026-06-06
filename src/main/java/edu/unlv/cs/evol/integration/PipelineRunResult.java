package edu.unlv.cs.evol.integration;

import edu.unlv.cs.evol.integration.database.MergeResult;
import edu.unlv.cs.evol.integration.database.Patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed per-run record of what happened to each evaluated PR, replacing
 * grep-the-log as the only way to know how a pipeline run went. Collected by
 * {@link RePatchIntegration} and printed as an end-of-run summary — one
 * greppable {@code [PipelineSummary]} line per PR plus a totals line — while
 * the database connection is still open so verdicts can be included.
 */
public class PipelineRunResult {

    /** How a single PR's merge scenario ended. */
    public enum ScenarioOutcome {
        /** Conflicting scenario evaluated; merge_result rows written. */
        EVALUATED,
        /** Cherry-pick applied cleanly — nothing for RePatch to do. */
        NON_CONFLICTING,
        /** Marked done by a previous run; skipped. */
        ALREADY_DONE,
        /** No base commit could be determined; skipped. */
        NO_BASE_COMMIT,
        /** Threw — classified by the [Pipeline] SCENARIO_FAILED log line. */
        FAILED
    }

    private static class Entry {
        final int prNumber;
        final ScenarioOutcome outcome;
        final long elapsedMs;
        final String detail;

        Entry(int prNumber, ScenarioOutcome outcome, long elapsedMs, String detail) {
            this.prNumber = prNumber;
            this.outcome = outcome;
            this.elapsedMs = elapsedMs;
            this.detail = detail;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final long startedAtMs = System.currentTimeMillis();

    public void record(int prNumber, ScenarioOutcome outcome, long elapsedMs, String detail) {
        entries.add(new Entry(prNumber, outcome, elapsedMs, detail));
    }

    /**
     * Print the summary. Must be called while the ActiveJDBC connection is
     * open — verdict lookup reads merge_result through the models.
     */
    public void printSummary() {
        System.out.println("================ [PipelineSummary] ================");
        int failed = 0;
        for (Entry entry : entries) {
            StringBuilder line = new StringBuilder("[PipelineSummary] PR ").append(entry.prNumber)
                    .append(" — ").append(entry.outcome)
                    .append(" in ").append(entry.elapsedMs / 1000).append("s");
            String verdicts = verdictsFor(entry.prNumber);
            if (!verdicts.isEmpty()) {
                line.append(" (").append(verdicts).append(")");
            }
            if (entry.detail != null) {
                line.append(" — ").append(entry.detail);
            }
            if (entry.outcome == ScenarioOutcome.FAILED) {
                failed++;
            }
            System.out.println(line);
        }
        System.out.println("[PipelineSummary] " + entries.size() + " PRs, " + failed + " failed, total "
                + (System.currentTimeMillis() - startedAtMs) / 1000 + "s");
        System.out.println("====================================================");
    }

    /** e.g. "RePatch 1/1/6, Git-CherryPick 1/1/6" — empty when no rows exist. */
    private String verdictsFor(int prNumber) {
        try {
            Patch patch = Patch.findFirst("number = ?", prNumber);
            if (patch == null) {
                return "";
            }
            List<MergeResult> results = MergeResult.where("patch_id = ?", patch.getId()).orderBy("merge_tool");
            StringBuilder verdicts = new StringBuilder();
            for (MergeResult result : results) {
                if (verdicts.length() > 0) {
                    verdicts.append(", ");
                }
                verdicts.append(result.getString("merge_tool")).append(" ")
                        .append(result.getInteger("total_conflicting_files")).append("/")
                        .append(result.getInteger("total_conflicts")).append("/")
                        .append(result.getInteger("total_conflicting_loc"));
            }
            return verdicts.toString();
        } catch (RuntimeException e) {
            // a broken verdict lookup must never break the summary itself
            return "verdict lookup failed: " + e.getMessage();
        }
    }
}
