package edu.unlv.cs.evol.repatch.replayOperations;

import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameFieldObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.MoveMembersRefactoring;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.usageView.UsageInfo;

/**
 * Replays a rename/move field refactoring on the merged result. Migrated
 * onto the Week 4A contract.
 *
 * Operation family: move/rename (field).
 *  - Expected PSI inputs: the original class resolvable from its file path
 *    (field refactorings replay before class refactorings, so the class
 *    still bears its original name), and the original field present on it.
 *  - Side effects: field renamed to its destination name and/or moved to
 *    the destination class; references updated by the platform processors.
 *  - Failure behavior: unresolvable class/field classify as
 *    PRECONDITION_FAILED before mutation. The legacy class asserted the
 *    field lookup (a disabled-assert NPE in production); it is a classified
 *    precondition now.
 *  - Postcondition: a field with the destination name is resolvable on the
 *    class the replay targeted (destination class for moves, original
 *    class otherwise).
 */
public class ReplayMoveRenameField implements RefactoringOperation {

    private final MoveRenameFieldObject fieldObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiField psiField;
    private VirtualFile vFile;

    public ReplayMoveRenameField(RefactoringObject refactoringObject) {
        this.fieldObject = (MoveRenameFieldObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "replay " + fieldObject.getRefactoringType()
                + " (" + fieldObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed {
        Project project = context.getProject();
        // The file and class that we are replaying the refactoring in. We use
        // the original instead of the destination because we replay the field
        // refactorings before the class refactorings.
        String originalFile = fieldObject.getOriginalFilePath();
        String originalClass = fieldObject.getOriginalClass();

        context.getProjectRoots().addSourceRoot(project, originalFile, originalClass);

        // Use original class for both rename + move
        psiClass = context.getPsiSearch().findClass(project, originalClass, originalFile);
        if (psiClass == null) {
            throw new PreconditionFailed("class " + originalClass
                    + " not resolvable from " + originalFile);
        }
        vFile = psiClass.getContainingFile().getVirtualFile();

        psiField = PsiSearchService.findField(psiClass, fieldObject.getOriginalName());
        if (psiField == null) {
            throw new PreconditionFailed("original field " + fieldObject.getOriginalName()
                    + " not found on " + psiClass.getQualifiedName());
        }
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        String renamedField = fieldObject.getDestinationName();
        String destinationClass = fieldObject.getDestinationClass();

        if (fieldObject.isRename()) {
            RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
            // Rename the original field to the refactored field
            RenameRefactoring renameRefactoring = factory.createRename(psiField, renamedField, true, true);
            UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
            renameRefactoring.doRefactoring(refactoringUsages);
        }
        if (fieldObject.isMove()) {
            JavaRefactoringFactory refactoringFactory = JavaRefactoringFactory.getInstance(project);
            String visibility = fieldObject.getVisibility();
            PsiMember[] psiMembers = new PsiMember[1];
            psiMembers[0] = psiField;
            MoveMembersRefactoring moveFieldRefactoring = refactoringFactory.createMoveMembers(psiMembers,
                    destinationClass, visibility);
            UsageInfo[] refactoringUsages = moveFieldRefactoring.findUsages();
            moveFieldRefactoring.doRefactoring(refactoringUsages);
        }

        context.getPlatform().closeActiveUsageView(project);
        // Update the virtual file that contains the refactoring
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        String renamedField = fieldObject.getDestinationName();
        String targetClass = fieldObject.isMove()
                ? fieldObject.getDestinationClass() : fieldObject.getOriginalClass();
        String targetFile = fieldObject.isMove()
                ? fieldObject.getDestinationFilePath() : fieldObject.getOriginalFilePath();
        PsiClass replayedClass = context.getPsiSearch()
                .findClass(context.getProject(), targetClass, targetFile);
        if (replayedClass == null) {
            return "target class " + targetClass + " not resolvable from " + targetFile;
        }
        if (PsiSearchService.findField(replayedClass, renamedField) == null) {
            return "field " + renamedField + " not present on " + targetClass + " after replay";
        }
        return null;
    }
}
