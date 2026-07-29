package edu.unlv.cs.evol.integration.database;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import org.javalite.activejdbc.Base;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone verification that a detected source<->target refactoring conflict
 * is persisted to the refactoring_conflict table (not swallowed, not crashing).
 *
 * Genuine matrix conflicts are rare in the evaluation corpus, so this check
 * constructs the canonical example directly: the target renamed poll() while
 * the source PR renamed the same method differently in the same class.
 *
 * Run against a database bootstrapped from create_integration_schema.sql:
 *   ./gradlew refactoringConflictPersistenceCheck
 * with the usual JDBC_URL / JDBC_USER / JDBC_PASSWORD environment.
 * Exits 0 and prints the persisted row on success; exits 1 otherwise.
 */
public class RefactoringConflictPersistenceCheck {

    public static void main(String[] args) {
        Base.open("com.mysql.jdbc.Driver", DatabaseUtils.getDatabaseUrl(),
                DatabaseUtils.getDatabaseUser(), DatabaseUtils.getDatabasePassword());
        try {
            // Minimal FK chain: project -> patch -> merge_commit -> merge_result
            Project project = new Project("https://github.com/apache/kafka", "kafka",
                    "https://github.com/linkedin/kafka", "kafka");
            project.saveIt();
            Patch patch = new Patch(0, "MO", 1, project);
            patch.saveIt();
            MergeCommit mergeCommit = new MergeCommit("0000000000000000000000000000000000000000", true,
                    "1111111111111111111111111111111111111111", "2222222222222222222222222222222222222222",
                    project, patch, "persistence-check", "persistence-check@localhost", 0L);
            mergeCommit.saveIt();
            MergeResult mergeResult = new MergeResult("RePatch", 0, 0, 0, 0L, mergeCommit);
            mergeResult.saveIt();

            // The conflicting pair: both sides rename Fetcher.poll(), incompatibly.
            List<ParameterObject> params = new ArrayList<>();
            params.add(new ParameterObject("void", "return"));
            MethodSignatureObject poll = new MethodSignatureObject(params, "poll");
            MethodSignatureObject pollRecords = new MethodSignatureObject(params, "pollRecords");
            MethodSignatureObject fetch = new MethodSignatureObject(params, "fetch");
            String path = "clients/src/main/java/org/apache/kafka/clients/consumer/internals/Fetcher.java";
            MoveRenameMethodObject left = new MoveRenameMethodObject(
                    path, "Fetcher", poll, path, "Fetcher", pollRecords);
            MoveRenameMethodObject right = new MoveRenameMethodObject(
                    path, "Fetcher", poll, path, "Fetcher", fetch);

            new RefactoringConflict(left, right, mergeResult).saveIt();

            long rows = RefactoringConflict.count("merge_result_id = ?", mergeResult.getId());
            RefactoringConflict persisted = RefactoringConflict.findFirst("merge_result_id = ?", mergeResult.getId());
            System.out.println("refactoring_conflict rows for this merge_result: " + rows);
            System.out.println("persisted: left=" + persisted.get("left_refactoring_type")
                    + " (" + persisted.get("left_old_path") + ")"
                    + " right=" + persisted.get("right_refactoring_type")
                    + " (" + persisted.get("right_old_path") + ")");
            if (rows == 1) {
                System.out.println("PERSISTENCE CHECK PASSED");
                System.exit(0);
            }
            System.out.println("PERSISTENCE CHECK FAILED: expected 1 row, found " + rows);
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("PERSISTENCE CHECK FAILED: " + e.getMessage());
            System.exit(1);
        } finally {
            Base.close();
        }
    }
}
