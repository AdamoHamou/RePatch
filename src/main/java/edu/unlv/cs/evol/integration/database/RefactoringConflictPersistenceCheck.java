package edu.unlv.cs.evol.integration.database;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import org.apache.commons.lang3.tuple.Pair;
import org.javalite.activejdbc.Base;
import org.refactoringminer.api.RefactoringType;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone verification that a detected source-vs-target refactoring conflict
 * is persisted to the refactoring_conflict table (not swallowed, not crashing).
 *
 * Genuine matrix conflicts are rare in the evaluation corpus, so this check
 * constructs the canonical example from REFACTORING_CONFLICT_TRACKING.md
 * directly: the target moved Fetcher.poll() to ConsumerCoordinator while the
 * source PR renamed the same method within Fetcher. The move-side detail is
 * padded past VARCHAR(2000) to cover the truncation guard.
 *
 * Phase 2 exercises the production save loop (RefactoringConflict.persistAll):
 * a row whose insert fails must be logged and skipped without aborting the
 * rows that follow it.
 *
 * Run against a database bootstrapped from create_integration_schema.sql:
 *   ./gradlew refactoringConflictPersistenceCheck
 * with the usual JDBC_URL / JDBC_USER / JDBC_PASSWORD environment.
 * Exits 0 and prints the persisted row on success; exits 1 otherwise.
 */
public class RefactoringConflictPersistenceCheck {

    private static final List<String> failures = new ArrayList<>();

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

            String fetcherPath = "clients/src/main/java/org/apache/kafka/clients/consumer/internals/Fetcher.java";
            String coordinatorPath = "clients/src/main/java/org/apache/kafka/clients/consumer/internals/ConsumerCoordinator.java";
            List<ParameterObject> params = new ArrayList<>();
            params.add(new ParameterObject("void", "return"));
            MethodSignatureObject poll = new MethodSignatureObject(params, "poll");
            MethodSignatureObject pollRecords = new MethodSignatureObject(params, "pollRecords");

            // Left (target HEAD): Move Method poll() Fetcher -> ConsumerCoordinator,
            // with a detail longer than the column's VARCHAR(2000).
            StringBuilder longDetail = new StringBuilder(
                    "Move Method public poll() : void from class org.apache.kafka.clients.consumer.internals.Fetcher"
                    + " to public poll() : void from class org.apache.kafka.clients.consumer.internals.ConsumerCoordinator ");
            while (longDetail.length() < 2500) {
                longDetail.append('#');
            }
            MoveRenameMethodObject left = new MoveRenameMethodObject(
                    RefactoringType.MOVE_OPERATION, longDetail.toString(),
                    fetcherPath, "Fetcher", poll,
                    coordinatorPath, "ConsumerCoordinator", poll);
            // Right (source PR): Rename Method poll() -> pollRecords() within Fetcher.
            String renameDetail = "Rename Method public poll() : void renamed to public pollRecords() : void"
                    + " in class org.apache.kafka.clients.consumer.internals.Fetcher";
            MoveRenameMethodObject right = new MoveRenameMethodObject(
                    RefactoringType.RENAME_METHOD, renameDetail,
                    fetcherPath, "Fetcher", poll,
                    fetcherPath, "Fetcher", pollRecords);

            new RefactoringConflict(left, right, mergeResult).saveIt();

            long rows = RefactoringConflict.count("merge_result_id = ?", mergeResult.getId());
            expect("rows", 1L, rows);
            RefactoringConflict persisted = RefactoringConflict.findFirst("merge_result_id = ?", mergeResult.getId());
            expect("left_refactoring_type", "Move Method", persisted.getString("left_refactoring_type"));
            expect("right_refactoring_type", "Rename Method", persisted.getString("right_refactoring_type"));
            expect("left_old_path", fetcherPath, persisted.getString("left_old_path"));
            expect("left_new_path", coordinatorPath, persisted.getString("left_new_path"));
            expect("right_old_path", fetcherPath, persisted.getString("right_old_path"));
            expect("right_new_path", fetcherPath, persisted.getString("right_new_path"));
            expect("left_refactoring_detail truncated to 2000", longDetail.substring(0, 2000),
                    persisted.getString("left_refactoring_detail"));
            expect("right_refactoring_detail", renameDetail, persisted.getString("right_refactoring_detail"));
            System.out.println("persisted: left=" + persisted.get("left_refactoring_type")
                    + " (" + persisted.get("left_old_path") + " -> " + persisted.get("left_new_path") + ")"
                    + " right=" + persisted.get("right_refactoring_type")
                    + " (" + persisted.get("right_old_path") + " -> " + persisted.get("right_new_path") + ")"
                    + " left_detail_length=" + persisted.getString("left_refactoring_detail").length());

            // Phase 2: the guarded save loop. The first row's paths exceed
            // VARCHAR(1000) — paths are not truncated by the model, so its insert
            // fails; persistAll must log it and still persist the row after it.
            // Pin the schema script's sql_mode so over-length errors on any server.
            Base.exec("SET SESSION sql_mode = 'TRADITIONAL,ALLOW_INVALID_DATES'");
            MergeResult mergeResult2 = new MergeResult("RePatch", 0, 0, 0, 0L, mergeCommit);
            mergeResult2.saveIt();
            String longPath = "x".repeat(1100) + ".java";
            MoveRenameMethodObject badLeft = new MoveRenameMethodObject(
                    longPath, "Fetcher", poll, longPath, "Fetcher", pollRecords);
            List<Pair<RefactoringObject, RefactoringObject>> pairs = new ArrayList<>();
            pairs.add(Pair.of(badLeft, right));
            pairs.add(Pair.of(left, right));
            RefactoringConflict.persistAll(pairs, mergeResult2, patch.getNumber());
            long rows2 = RefactoringConflict.count("merge_result_id = ?", mergeResult2.getId());
            expect("guarded-loop rows (bad row skipped, good row persisted)", 1L, rows2);

            if (failures.isEmpty()) {
                System.out.println("PERSISTENCE CHECK PASSED");
                System.exit(0);
            }
            for (String failure : failures) {
                System.out.println("MISMATCH " + failure);
            }
            System.out.println("PERSISTENCE CHECK FAILED: " + failures.size() + " mismatch(es)");
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("PERSISTENCE CHECK FAILED: " + e.getMessage());
            System.exit(1);
        } finally {
            Base.close();
        }
    }

    private static void expect(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures.add(what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }
}
