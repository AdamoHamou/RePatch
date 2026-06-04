package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionResult;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionService;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatcher for the invert flow. Each refactoring is routed through the
 * Week 4A {@link RefactoringExecutionService} contract, so every failure
 * is classified and logged instead of silently counted; operation classes
 * not yet migrated run through {@link RefactoringOperation#legacy}.
 */
public class InvertRefactorings {
    /*
     * invertRefactorings takes a list of refactorings and performs the inverse for each one.
     */
    public static int invertRefactorings(ArrayList<RefactoringObject> refactoringObjects,
                                         RefactoringExecutionContext context) {
        long time = System.currentTimeMillis();
        RefactoringExecutionService executionService = new RefactoringExecutionService(context);
        Project project = context.getProject();

        int failedRefactorings = 0;
        // Iterate through the list of refactorings and undo each one
        for (int i = 0; i < refactoringObjects.size(); i++) {
            RefactoringObject refactoringObject = refactoringObjects.get(i);
            long time2 = System.currentTimeMillis();
            // If it has been 14 minutes, it will take more than 15 minutes to complete RePatch
            if ((time2 - time) > 780000) {
                System.out.println("RePatch Timed Out");
                // Save all of the refactoring changes from memory onto disk
                context.getPlatform().saveAllDocuments();
                return failedRefactorings;
            }

            // InvertExtractMethod may hand back a replacement refactoring
            // object that has to take the original's place in the list.
            AtomicReference<RefactoringObject> replacement = new AtomicReference<>();
            RefactoringOperation operation = operationFor(refactoringObject, project, replacement);
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
            if (replacement.get() != null) {
                refactoringObjects.set(i, replacement.get());
            }
        }
        // Save all of the refactoring changes from memory onto disk
        context.getPlatform().saveAllDocuments();
        return failedRefactorings;
    }

    /*
     * Map a refactoring object to its inverse operation. Returns null for
     * refactoring types RePatch does not invert.
     */
    private static RefactoringOperation operationFor(RefactoringObject refactoringObject, Project project,
                                                     AtomicReference<RefactoringObject> replacement) {
        String description = "invert " + refactoringObject.getRefactoringType()
                + " (" + refactoringObject.getRefactoringDetail() + ")";
        switch (refactoringObject.getRefactoringType()) {
            case RENAME_CLASS:
            case MOVE_CLASS:
            case MOVE_RENAME_CLASS:
                return RefactoringOperation.legacy(description,
                        () -> new InvertMoveRenameClass(project).invertMoveRenameClass(refactoringObject));
            case RENAME_METHOD:
            case MOVE_OPERATION:
            case MOVE_AND_RENAME_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new InvertMoveRenameMethod(project).invertMoveRenameMethod(refactoringObject));
            case EXTRACT_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> replacement.set(new InvertExtractMethod(project).invertExtractMethod(refactoringObject)));
            case INLINE_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new InvertInlineMethod(project).invertInlineMethod(refactoringObject));
            case RENAME_ATTRIBUTE:
            case MOVE_ATTRIBUTE:
            case MOVE_RENAME_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new InvertMoveRenameField(project).invertRenameField(refactoringObject));
            case PULL_UP_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new InvertPullUpMethod(project).invertPullUpMethod(refactoringObject));
            case PUSH_DOWN_OPERATION:
                return RefactoringOperation.legacy(description,
                        () -> new InvertPushDownMethod(project).invertPushDownMethod(refactoringObject));
            case PULL_UP_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new InvertPullUpField(project).invertPullUpField(refactoringObject));
            case PUSH_DOWN_ATTRIBUTE:
                return RefactoringOperation.legacy(description,
                        () -> new InvertPushDownField(project).invertPushDownField(refactoringObject));
            case RENAME_PACKAGE:
                return RefactoringOperation.legacy(description,
                        () -> new InvertRenamePackage(project).invertRenamePackage(refactoringObject));
            case RENAME_PARAMETER:
                return RefactoringOperation.legacy(description,
                        () -> new InvertRenameParameter(project).invertRenameParameter(refactoringObject));
            default:
                return null;
        }
    }
}
