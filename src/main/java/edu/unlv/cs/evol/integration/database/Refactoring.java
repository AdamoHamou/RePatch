package edu.unlv.cs.evol.integration.database;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

@Table("refactoring")
public class Refactoring extends Model {

    public Refactoring() {}

    public Refactoring(String refactoringType, String refactoringDetail, MergeCommit mergeCommit) {
        set("refactoring_type", refactoringType, "merge_commit_id", mergeCommit.getId(), "project_id", mergeCommit.getProjectId(),
                "patch_id", mergeCommit.getPatchId(), "refactoring_detail", refactoringDetail);
    }
}
