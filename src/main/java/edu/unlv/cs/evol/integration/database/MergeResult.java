package edu.unlv.cs.evol.integration.database;

import edu.unlv.cs.evol.integration.data.ComparisonResult;
import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

@Table("merge_result")
public class MergeResult extends Model {


    public MergeResult() {
    }

    public MergeResult(String mergeTool, int totalConflictingFiles, int totalConflicts, int totalConflictingLOC,
                       long runtime, MergeCommit mergeCommit) {
        set("merge_tool", mergeTool,
                "total_conflicting_files", totalConflictingFiles,
                "total_conflicts", totalConflicts,
                "total_conflicting_loc", totalConflictingLOC,
                "runtime", runtime,
                "merge_commit_id", mergeCommit.getId(),
                "project_id", mergeCommit.getProjectId(),
                "patch_id", mergeCommit.getPatchId());
    }

    public MergeResult(String mergeTool, int totalConflicts, int totalConflictingLOC,
                       long runtime, ComparisonResult result, MergeCommit mergeCommit) {
        set("merge_tool", mergeTool,
                "total_conflicts", totalConflicts,
                "total_conflicting_loc", totalConflictingLOC,
                "total_diff_files", result.getTotalDiffFiles(),
                "auto_merged_precision", result.getPrecision(),
                "auto_merged_recall", result.getRecall(),
                "runtime", runtime,
                "total_auto_merged_loc", result.getTotalAutoMergedLOC(),
                "total_manual_merged_loc", result.getTotalManualMergedLOC(),
                "total_same_loc_merged", result.getTotalSameLOCMerged(),
                "total_same_loc_manual", result.getTotalSameLOCManual(),
                "merge_commit_id", mergeCommit.getId(),
                "project_id", mergeCommit.getProjectId(),
                "patch_id", mergeCommit.getPatchId());
    }

    public String getMergeTool() {
        return getString("merge_tool");
    }

    public int getMergeCommitId() {
        return getInteger("merge_commit_id");
    }

    public int getProjectId() {
        return getInteger("project_id");
    }
    public int getPatchId() {
        return getInteger("patch_id");
    }
}
