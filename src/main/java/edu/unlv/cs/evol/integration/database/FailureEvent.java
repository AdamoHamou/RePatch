package edu.unlv.cs.evol.integration.database;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

/**
 * One classified pipeline failure, persisted from a drained
 * {@code FailureEventSink.Entry} and stamped with the scenario it belongs to.
 * The project/patch/merge-commit refs are FK-style like {@link MergeResult}'s
 * but nullable — a scenario can fail before its merge_commit row exists.
 * ActiveJDBC manages created_at/updated_at because the columns are present.
 */
@Table("failure_event")
public class FailureEvent extends Model {

    // MySQL TEXT tops out at 65535 bytes; evidence strings are log lines but
    // guard anyway so one pathological detail can't kill a scenario's writes.
    private static final int MAX_EVIDENCE_LENGTH = 65000;

    public FailureEvent() {
    }

    public FailureEvent(String phase, String refactoringType, String category, String evidence,
                        boolean isPipelineArtifact, String operationId,
                        Object projectId, Object patchId, Object mergeCommitId) {
        if (evidence != null && evidence.length() > MAX_EVIDENCE_LENGTH) {
            evidence = evidence.substring(0, MAX_EVIDENCE_LENGTH);
        }
        set("phase", phase,
                "refactoring_type", refactoringType,
                "category", category,
                "evidence", evidence,
                "is_pipeline_artifact", isPipelineArtifact,
                "operation_id", operationId,
                "project_id", projectId,
                "patch_id", patchId,
                "merge_commit_id", mergeCommitId);
    }

    public String getPhase() {
        return getString("phase");
    }

    public String getCategory() {
        return getString("category");
    }
}
