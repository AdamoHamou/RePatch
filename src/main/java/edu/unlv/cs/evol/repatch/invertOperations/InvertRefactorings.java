package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;

// To Do: Refactor to call invert/replay from objects
public class InvertRefactorings {
    /*
     * invertRefactorings takes a list of refactorings and performs the inverse for each one.
     */
    public static int invertRefactorings(ArrayList<RefactoringObject> refactoringObjects,
                                                                  Project project) {
        // PSI lookups below die with IndexNotReadyException if indexing is
        // still running (small repos reach this point before initial indexing
        // completes); make sure the index is ready and the workspace roots
        // have stopped moving at phase entry.
        Utils.waitForWorkspaceSettle(project);
        System.out.println("MODELPROBE invert-entry modules="
                + com.intellij.openapi.module.ModuleManager.getInstance(project).getModules().length
                + " contentRoots="
                + com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getContentRoots().length);
        long time = System.currentTimeMillis();

        int failedRefactorings = 0;
        // Iterate through the list of refactorings and undo each one
        for(RefactoringObject refactoringObject : refactoringObjects) {
            long time2 = System.currentTimeMillis();
            // If it has been 14 minutes, it will take more than 15 minutes to complete RePatch
            if((time2 - time) > 780000) {
                System.out.println("RePatch Timed Out");
                // Save all of the refactoring changes from memory onto disk
                FileDocumentManager.getInstance().saveAllDocuments();
                return failedRefactorings;
            }
            // Single catch site (SPEC-4): a refactoring whose inversion throws
            // is counted AND excluded from replay — replaying it later would
            // re-apply a refactoring whose inverse never happened, on a tree
            // that does not match its assumptions.
            try {
                switch (refactoringObject.getRefactoringType()) {
                    case RENAME_CLASS:
                    case MOVE_CLASS:
                    case MOVE_RENAME_CLASS:
                        new InvertMoveRenameClass(project).invertMoveRenameClass(refactoringObject);
                        break;
                    case RENAME_METHOD:
                    case MOVE_OPERATION:
                    case MOVE_AND_RENAME_OPERATION:
                        new InvertMoveRenameMethod(project).invertMoveRenameMethod(refactoringObject);
                        break;
                    case EXTRACT_OPERATION:
                        InvertExtractMethod invertExtractMethod = new InvertExtractMethod(project);
                        RefactoringObject inverted = invertExtractMethod.invertExtractMethod(refactoringObject);
                        if (inverted != null) {
                            int index = refactoringObjects.indexOf(inverted);
                            if (index >= 0) {
                                refactoringObjects.set(index, inverted);
                            }
                            refactoringObject = inverted;
                        }
                        break;
                    case INLINE_OPERATION:
                        new InvertInlineMethod(project).invertInlineMethod(refactoringObject);
                        break;
                    case RENAME_ATTRIBUTE:
                    case MOVE_ATTRIBUTE:
                    case MOVE_RENAME_ATTRIBUTE:
                        new InvertMoveRenameField(project).invertRenameField(refactoringObject);
                        break;
                    case PULL_UP_OPERATION:
                        new InvertPullUpMethod(project).invertPullUpMethod(refactoringObject);
                        break;
                    case PUSH_DOWN_OPERATION:
                        new InvertPushDownMethod(project).invertPushDownMethod(refactoringObject);
                        break;
                    case PULL_UP_ATTRIBUTE:
                        new InvertPullUpField(project).invertPullUpField(refactoringObject);
                        break;
                    case PUSH_DOWN_ATTRIBUTE:
                        new InvertPushDownField(project).invertPushDownField(refactoringObject);
                        break;
                    case RENAME_PACKAGE:
                        new InvertRenamePackage(project).invertRenamePackage(refactoringObject);
                        break;
                    case RENAME_PARAMETER:
                        new InvertRenameParameter(project).invertRenameParameter(refactoringObject);
                        break;
                }
            } catch (Exception e) {
                failedRefactorings++;
                markInversionFailed(refactoringObject, e);
            }
            // Each inversion's edits must be committed and indexed before the
            // next inversion's findUsages runs, or usages are missed
            // nondeterministically.
            Utils.settleAfterPsiEdit(project);

        }
        // Save all of the refactoring changes from memory onto disk
        FileDocumentManager.getInstance().saveAllDocuments();
        return failedRefactorings;
    }

    /*
     * SPEC-4: a failed inversion leaves this refactoring un-inverted in the
     * working tree; excluding it from replay (Matrix.detectConflicts only
     * replays isReplay() objects) prevents re-applying it on a tree where its
     * inverse never happened.
     */
    static void markInversionFailed(RefactoringObject refactoringObject, Exception e) {
        refactoringObject.setReplayFlag(false);
        System.out.println("-> Inversion failed; excluded from replay: "
                + refactoringObject.getRefactoringType()
                + " (" + refactoringObject.getOriginalFilePath() + "): " + e);
        e.printStackTrace();
    }
}
