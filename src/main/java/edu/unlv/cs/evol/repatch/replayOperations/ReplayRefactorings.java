package edu.unlv.cs.evol.repatch.replayOperations;

import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionResult;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionService;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;

/**
 * Dispatcher for the replay flow. Each refactoring is routed through the
 * Week 4A {@link RefactoringExecutionService} contract, so every failure
 * is classified and logged instead of silently swallowed; operation
 * classes not yet migrated run through {@link RefactoringOperation#legacy}.
 */
public class ReplayRefactorings {

    /*
     * replayRefactorings takes a list of refactorings and performs each of the refactorings.
     */
    public static int replayRefactorings(ArrayList<RefactoringObject> refactoringObjects,
                                         RefactoringExecutionContext context) {
        RefactoringExecutionService executionService = new RefactoringExecutionService(context);
        Project project = context.getProject();

        int failedRefactorings = 0;
        for (RefactoringObject refactoringObject : refactoringObjects) {
            RefactoringOperation operation = operationFor(refactoringObject, project);
            if (operation == null) {
                // Refactoring types outside the supported families.
                continue;
            }
            RefactoringExecutionResult result = executionService.execute(operation);
            if (!result.isSuccess()) {
                failedRefactorings++;
                Utils.log(project.getName(), result.describe());
                if (result.getFailure() != null) {
                    result.getFailure().printStackTrace();
                }
            }
        }

        // Save all of the refactoring changes from memory onto disk
        context.getPlatform().saveAllDocuments();
        return failedRefactorings;
    }

    /*
     * Map a refactoring object to its replay operation. Returns null for
     * refactoring types RePatch does not replay.
     */
    private static RefactoringOperation operationFor(RefactoringObject refactoringObject, Project project) {
        String description = "replay " + refactoringObject.getRefactoringType()
                + " (" + refactoringObject.getRefactoringDetail() + ")";
        switch (refactoringObject.getRefactoringType()) {
            case RENAME_CLASS:
            case MOVE_CLASS:
            case MOVE_RENAME_CLASS:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayMoveRenameClass(project).replayMoveRenameClass(refactoringObject));
            case RENAME_METHOD:
            case MOVE_OPERATION:
            case MOVE_AND_RENAME_OPERATION:
                // Migrated onto the 4A contract: implements RefactoringOperation directly.
                return new ReplayMoveRenameMethod(refactoringObject);
            case EXTRACT_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayExtractMethod(project).replayExtractMethod(refactoringObject));
            case INLINE_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayInlineMethod(project).replayInlineMethod(refactoringObject));
            case RENAME_ATTRIBUTE:
            case MOVE_ATTRIBUTE:
            case MOVE_RENAME_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayMoveRenameField(project).replayRenameField(refactoringObject));
            case PULL_UP_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayPullUpMethod(project).replayPullUpMethod(refactoringObject));
            case PUSH_DOWN_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayPushDownMethod(project).replayPushDownMethod(refactoringObject));
            case PULL_UP_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayPullUpField(project).replayPullUpField(refactoringObject));
            case PUSH_DOWN_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayPushDownField(project).replayPushDownField(refactoringObject));
            case RENAME_PACKAGE:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayRenamePackage(project).replayRenamePackage(refactoringObject));
            case RENAME_PARAMETER:
                return RefactoringOperation.legacy(description,
                        () -> new ReplayRenameParameter(project).replayRenameParameter(refactoringObject));
            default:
                return null;
        }
    }
}
