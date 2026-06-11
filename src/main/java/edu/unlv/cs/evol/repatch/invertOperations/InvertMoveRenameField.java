package edu.unlv.cs.evol.repatch.invertOperations;

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
 * Inverts a rename/move field refactoring by renaming/moving the field
 * back. Migrated onto the Week 4A contract.
 *
 * Operation family: move/rename (field).
 *  - Expected PSI inputs: the destination class (for moves, with a fallback
 *    to the original class for misidentified refactorings) or original
 *    class (rename-only), resolvable from its file path, and the refactored
 *    field present on it.
 *  - Side effects: field renamed to its original name and/or moved back to
 *    the original class; references updated by the platform processors.
 *  - Failure behavior: unresolvable class/field classify as
 *    PRECONDITION_FAILED before mutation. The legacy class only null-checked
 *    the field on the rename branch and passed a possibly-null member into
 *    the move processor (the 12660 null-target path); both branches now
 *    fail the precondition instead.
 *  - Postcondition: a field with the original name is resolvable on the
 *    original class.
 */
public class InvertMoveRenameField implements RefactoringOperation {

    private final MoveRenameFieldObject fieldObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiField psiField;
    private VirtualFile vFile;

    public InvertMoveRenameField(RefactoringObject refactoringObject) {
        this.fieldObject = (MoveRenameFieldObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "invert " + fieldObject.getRefactoringType()
                + " (" + fieldObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed {
        Project project = context.getProject();
        // The file and class that we are inverting the refactoring in. We use
        // the original instead of the destination because the class
        // refactorings were already inverted.
        String originalFile = fieldObject.getOriginalFilePath();
        String originalClass = fieldObject.getOriginalClass();
        String destinationFile = fieldObject.getDestinationFilePath();
        String destinationClass = fieldObject.getDestinationClass();

        context.getProjectRoots().addSourceRoot(project, originalFile, originalClass);

        // If it is a Move Field or Rename+Move Field refactoring, use the destination class
        if (fieldObject.isMove()) {
            psiClass = context.getPsiSearch().findClass(project, destinationClass, destinationFile);
            if (psiClass == null) {
                // Try original class in case of misidentified refactoring
                psiClass = context.getPsiSearch().findClass(project, originalClass, originalFile);
            }
        } else if (fieldObject.isRename()) {
            psiClass = context.getPsiSearch().findClass(project, originalClass, originalFile);
        }
        if (psiClass == null) {
            throw new PreconditionFailed("class " + originalClass
                    + " not resolvable from " + originalFile);
        }
        vFile = psiClass.getContainingFile().getVirtualFile();

        psiField = PsiSearchService.findField(psiClass, fieldObject.getDestinationName());
        if (psiField == null) {
            throw new PreconditionFailed("refactored field " + fieldObject.getDestinationName()
                    + " not found on " + psiClass.getQualifiedName());
        }
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        String originalField = fieldObject.getOriginalName();
        String originalClass = fieldObject.getOriginalClass();

        // If the field object is a rename field, perform the rename field first
        if (fieldObject.isRename()) {
            RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
            RenameRefactoring renameRefactoring = factory.createRename(psiField, originalField, true, true);
            UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
            renameRefactoring.doRefactoring(refactoringUsages);
        }
        // Now, if the field was moved, undo the move by performing a move field refactoring to move it to the
        // original class
        if (fieldObject.isMove()) {
            JavaRefactoringFactory refactoringFactory = JavaRefactoringFactory.getInstance(project);
            String visibility = fieldObject.getVisibility();
            PsiMember[] psiMembers = new PsiMember[1];
            psiMembers[0] = psiField;
            MoveMembersRefactoring moveFieldRefactoring = refactoringFactory.createMoveMembers(psiMembers,
                    originalClass, visibility);
            UsageInfo[] refactoringUsages = moveFieldRefactoring.findUsages();
            moveFieldRefactoring.doRefactoring(refactoringUsages);
        }

        context.getPlatform().closeActiveUsageView(project);
        // Update the virtual file that contains the refactoring
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        String originalField = fieldObject.getOriginalName();
        String originalClass = fieldObject.getOriginalClass();
        String originalFile = fieldObject.getOriginalFilePath();
        PsiClass invertedClass = context.getPsiSearch()
                .findClass(context.getProject(), originalClass, originalFile);
        if (invertedClass == null) {
            return "original class " + originalClass + " not resolvable from " + originalFile;
        }
        if (PsiSearchService.findField(invertedClass, originalField) == null) {
            return "field " + originalField + " not restored on " + originalClass;
        }
        return null;
    }
}
