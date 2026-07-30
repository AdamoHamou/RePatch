package edu.unlv.cs.evol.integration.database;

import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import org.apache.commons.lang3.tuple.Pair;
import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

import java.util.List;

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

    /*
     * Persists the detected refactoring conflicts for a merge scenario. One bad
     * row must not abort the whole patch.
     */
    public static void persistAll(List<Pair<RefactoringObject, RefactoringObject>> refactoringConflicts,
                                  MergeResult mergeResult, int patchNumber) {
        for (Pair<RefactoringObject, RefactoringObject> pair : refactoringConflicts) {
            try {
                RefactoringConflict refactoringConflict = new RefactoringConflict(pair.getLeft(), pair.getRight(), mergeResult);
                refactoringConflict.saveIt();
            } catch (Exception e) {
                System.out.println("Failed to persist refactoring conflict for patch "
                        + patchNumber + ": " + e.getMessage());
            }
        }
    }
}
