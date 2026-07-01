package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.MoveMembersRefactoring;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.usageView.UsageInfo;

/**
 * Inverts a rename/move method refactoring by renaming/moving the method
 * back. Migrated onto the Week 4A contract.
 *
 * Operation family: move/rename (method).
 *  - Expected PSI inputs: the destination class (for moves) or original
 *    class (rename-only) resolvable from the destination file path, and
 *    the refactored method signature present on it.
 *  - Side effects: method renamed to its original name and/or moved back
 *    to the original class; for moves the method-above anchor is recorded
 *    on the refactoring object so replay can restore ordering.
 *  - Failure behavior: unresolvable class/method classify as
 *    PRECONDITION_FAILED before mutation; processor blowups are
 *    PROCESSOR_THREW.
 *  - Postcondition: a method matching the original signature is
 *    resolvable on the original class.
 */
public class InvertMoveRenameMethod implements RefactoringOperation {

    private final MoveRenameMethodObject moveRenameMethodObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiMethod psiMethod;
    private VirtualFile vFile;

    public InvertMoveRenameMethod(RefactoringObject refactoringObject) {
        this.moveRenameMethodObject = (MoveRenameMethodObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "invert " + moveRenameMethodObject.getRefactoringType()
                + " (" + moveRenameMethodObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed {
        Project project = context.getProject();
        MethodSignatureObject refactored = moveRenameMethodObject.getDestinationMethodSignature();
        String originalClassName = moveRenameMethodObject.getOriginalClassName();
        String destinationClassName = moveRenameMethodObject.getOriginalDestinationClassName();
        // get the PSI class using the qualified class name
        String filePath = moveRenameMethodObject.getDestinationFilePath();

        context.getProjectRoots().addSourceRoot(project, filePath, destinationClassName);

        String className = moveRenameMethodObject.isMoveMethod() ? destinationClassName : originalClassName;
        psiClass = context.getPsiSearch().findClass(project, className, filePath);
        // The enclosing class may itself have been renamed in the same PR (e.g.
        // ConsumerTask -> PrimaryConsumerTask): RefactoringMiner records this
        // member with the old class name but the renamed destination file, so
        // name-based resolution can never match. Fall back to the file's primary
        // class. Whether the coupled class-rename inversion has already run
        // decides which file currently holds the class (the renamed-back
        // original file or the still-renamed destination file), and invert
        // order isn't guaranteed — so try both. The method is still under its
        // new name at invert time either way, so guard on the refactored
        // (destination) method to avoid binding an unrelated class.
        if (psiClass == null) {
            for (String candidatePath : new String[]{filePath, moveRenameMethodObject.getOriginalFilePath()}) {
                PsiClass byFile = context.getPsiSearch().findClassByFileNameMatch(project, candidatePath);
                if (byFile != null && PsiSearchService.findMethod(byFile, refactored) != null) {
                    psiClass = byFile;
                    break;
                }
            }
        }
        // If we cannot find the PSI class, do not try to invert the refactoring
        if (psiClass == null) {
            throw new PreconditionFailed("class " + className + " not resolvable from " + filePath);
        }
        vFile = psiClass.getContainingFile().getVirtualFile();
        psiMethod = PsiSearchService.findMethod(psiClass, refactored);
        if (psiMethod == null) {
            throw new PreconditionFailed("refactored method " + refactored.getName()
                    + " not found on " + className);
        }
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        MethodSignatureObject original = moveRenameMethodObject.getOriginalMethodSignature();
        String originalMethodName = original.getName();
        String originalClassName = moveRenameMethodObject.getOriginalClassName();

        // If the operation was renamed, undo the method refactoring by performing a method refactoring to change it
        // to the original operation
        if (moveRenameMethodObject.isRenameMethod()) {
            RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
            RenameRefactoring renameRefactoring = factory.createRename(psiMethod, originalMethodName, true, true);
            UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
            renameRefactoring.doRefactoring(refactoringUsages);
        }
        // If the operation was moved, undo the move method by performing a move method refactoring to move it to the
        // original class
        if (moveRenameMethodObject.isMoveMethod()) {
            // Get the method before the moved method so it can be moved to the correct location
            moveRenameMethodObject.setMethodAbove(getAboveMethodBeforeMove(psiClass, psiMethod));

            JavaRefactoringFactory refactoringFactory = JavaRefactoringFactory.getInstance(project);
            String visibility = original.getVisibility();
            PsiMember[] psiMembers = new PsiMember[1];
            psiMembers[0] = psiMethod;
            MoveMembersRefactoring moveMethodRefactoring = refactoringFactory.createMoveMembers(psiMembers,
                    originalClassName, visibility);
            UsageInfo[] refactoringUsages = moveMethodRefactoring.findUsages();
            moveMethodRefactoring.doRefactoring(refactoringUsages);
        }

        context.getPlatform().closeActiveUsageView(project);
        // Update the virtual file that contains the refactoring
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        MethodSignatureObject original = moveRenameMethodObject.getOriginalMethodSignature();
        String originalClassName = moveRenameMethodObject.getOriginalClassName();
        String originalFilePath = moveRenameMethodObject.getOriginalFilePath();
        PsiClass originalClass = context.getPsiSearch()
                .findClass(context.getProject(), originalClassName, originalFilePath);
        // Coupled-rename fallback (see prepare): when the enclosing class was
        // renamed in this PR, the original (name, file) pair never resolves even
        // though the method was correctly renamed back. Look in the file's
        // primary class on either side of the coupled class rename, accepting it
        // only if it carries the restored (original) method — so a genuinely
        // missing restore is still reported.
        if (originalClass == null) {
            for (String candidatePath : new String[]{originalFilePath, moveRenameMethodObject.getDestinationFilePath()}) {
                PsiClass byFile = context.getPsiSearch().findClassByFileNameMatch(context.getProject(), candidatePath);
                if (byFile != null && PsiSearchService.findMethod(byFile, original) != null) {
                    originalClass = byFile;
                    break;
                }
            }
        }
        if (originalClass == null) {
            return "original class " + originalClassName + " not resolvable from " + originalFilePath;
        }
        if (PsiSearchService.findMethod(originalClass, original) == null) {
            return "method " + original.getName() + " not restored on " + originalClassName;
        }
        return null;
    }

    /*
     * Get the method signature before the method that's moved so we can move it back to the same spot.
     */
    private String getAboveMethodBeforeMove(PsiClass psiClass, PsiMethod psiMethod) {
        String signatureString = null;
        PsiMethod[] psiMethods = psiClass.getMethods();
        for (int i = 0; i < psiMethods.length; i++) {
            PsiMethod otherMethod = psiMethods[i];
            if (psiMethod.getSignature(PsiSubstitutor.UNKNOWN).equals(otherMethod.getSignature(PsiSubstitutor.UNKNOWN))) {
                if (i == 0) {
                    break;
                }
                signatureString = psiMethods[i - 1].getSignature(PsiSubstitutor.UNKNOWN).toString();
                break;
            }
        }
        return signatureString;
    }
}
