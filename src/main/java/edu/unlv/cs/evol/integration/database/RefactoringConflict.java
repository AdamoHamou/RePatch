package edu.unlv.cs.evol.integration.database;

import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

@Table("refactoring_conflict")
public class RefactoringConflict extends Model {

    public RefactoringConflict() {}

    public RefactoringConflict(RefactoringObject left, RefactoringObject right, MergeResult mergeResult) {
        set("left_old_path", left.getOriginalFilePath(), "right_old_path", right.getOriginalFilePath(),
                "left_new_path", left.getDestinationFilePath(), "right_new_path", right.getDestinationFilePath(),
                "left_refactoring_type", left.getRefactoringType().getDisplayName(),
                "right_refactoring_type", right.getRefactoringType().getDisplayName(),
                "left_refactoring_detail", truncate(left.getRefactoringDetail()),
                "right_refactoring_detail", truncate(right.getRefactoringDetail()),
                "merge_result_id", mergeResult.getId(), "merge_commit_id", mergeResult.getMergeCommitId(),
                "project_id", mergeResult.getProjectId(),
                "patch_id", mergeResult.getPatchId());
    }

    // RefactoringMiner descriptions can exceed the column's VARCHAR(2000)
    private static String truncate(String detail) {
        return (detail != null && detail.length() > 2000) ? detail.substring(0, 2000) : detail;
    }
}
