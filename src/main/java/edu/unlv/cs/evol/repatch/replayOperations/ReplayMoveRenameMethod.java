package edu.unlv.cs.evol.repatch.replayOperations;

import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.MoveMembersRefactoring;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.usageView.UsageInfo;

/**
 * Replays a rename/move method refactoring. Migrated onto the Week 4A
 * contract.
 *
 * Operation family: move/rename (method).
 *  - Expected PSI inputs: the original class resolvable from the original
 *    file path with the original method signature present on it.
 *  - Side effects: method renamed to its destination name and/or moved to
 *    the destination class; after a move the method body is repositioned
 *    using the method-above anchor recorded during inversion.
 *  - Failure behavior: unresolvable class/method classify as
 *    PRECONDITION_FAILED before mutation; processor blowups are
 *    PROCESSOR_THREW.
 *  - Postcondition: a method matching the destination signature is
 *    resolvable on the destination class.
 */
public class ReplayMoveRenameMethod implements RefactoringOperation {

    private final MoveRenameMethodObject moveRenameMethodObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiMethod psiMethod;

    public ReplayMoveRenameMethod(RefactoringObject refactoringObject) {
        this.moveRenameMethodObject = (MoveRenameMethodObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "replay " + moveRenameMethodObject.getRefactoringType()
                + " (" + moveRenameMethodObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed {
        Project project = context.getProject();
        MethodSignatureObject original = moveRenameMethodObject.getOriginalMethodSignature();
        String originalClassName = moveRenameMethodObject.getOriginalClassName();
        String filePath = moveRenameMethodObject.getOriginalFilePath();

        psiClass = context.getPsiSearch().findClass(project, originalClassName, filePath);
        // If we fail to find the PSI class, do not try to replay
        if (psiClass == null) {
            throw new PreconditionFailed("class " + originalClassName + " not resolvable from " + filePath);
        }
        psiMethod = PsiSearchService.findMethod(psiClass, original);
        if (psiMethod == null) {
            throw new PreconditionFailed("original method " + original.getName()
                    + " not found on " + originalClassName);
        }
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        MethodSignatureObject original = moveRenameMethodObject.getOriginalMethodSignature();
        MethodSignatureObject renamed = moveRenameMethodObject.getDestinationMethodSignature();
        String destinationMethodName = renamed.getName();
        String destinationClassName = moveRenameMethodObject.getOriginalDestinationClassName();

        if (moveRenameMethodObject.isRenameMethod()) {
            RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
            RenameRefactoring renameRefactoring = factory.createRename(psiMethod, destinationMethodName, true, true);
            UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
            renameRefactoring.doRefactoring(refactoringUsages);
        }
        if (moveRenameMethodObject.isMoveMethod()) {
            JavaRefactoringFactory refactoringFactory = JavaRefactoringFactory.getInstance(project);
            String visibility = original.getVisibility();
            PsiMember[] psiMembers = new PsiMember[1];
            psiMembers[0] = psiMethod;
            MoveMembersRefactoring moveMethodRefactoring = refactoringFactory.createMoveMembers(psiMembers,
                    destinationClassName, visibility);
            UsageInfo[] refactoringUsages = moveMethodRefactoring.findUsages();
            moveMethodRefactoring.doRefactoring(refactoringUsages);
            psiClass = moveMethodRefactoring.getTargetClass();
            moveMethodToOriginallyMovedLocation(context, psiClass, psiMethod,
                    moveRenameMethodObject.getMethodAbove());
        }
        // Update the virtual file containing the refactoring
        if (psiClass != null) {
            VirtualFile vFile = psiClass.getContainingFile().getVirtualFile();
            vFile.refresh(false, true);
        }
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        MethodSignatureObject renamed = moveRenameMethodObject.getDestinationMethodSignature();
        String destinationClassName = moveRenameMethodObject.isMoveMethod()
                ? moveRenameMethodObject.getOriginalDestinationClassName()
                : moveRenameMethodObject.getOriginalClassName();
        String destinationFilePath = moveRenameMethodObject.getDestinationFilePath();
        PsiClass destinationClass = context.getPsiSearch()
                .findClass(context.getProject(), destinationClassName, destinationFilePath);
        if (destinationClass == null) {
            return "destination class " + destinationClassName + " not resolvable from " + destinationFilePath;
        }
        if (PsiSearchService.findMethod(destinationClass, renamed) == null) {
            return "method " + renamed.getName() + " not present on " + destinationClassName;
        }
        return null;
    }

    /*
     * Move the method to the correct location within the class using the method signature of the above method.
     */
    private void moveMethodToOriginallyMovedLocation(RefactoringExecutionContext context, PsiClass psiClass,
                                                     PsiMethod psiMethod, String aboveSignature) {
        if (context.getPlatform().isUnitTestMode()) {
            return;
        }
        if (psiClass == null) {
            return;
        }

        Project project = context.getProject();
        // Get all of the methods inside of the class.
        PsiMethod[] psiMethods = psiClass.getMethods();

        // Get the physical copy of the PSI method so we can delete it
        for (PsiMethod method : psiMethods) {
            boolean isSame = true;
            String m1 = method.getName();
            String m2 = psiMethod.getName();
            if (m1.equals(m2)) {
                PsiParameter[] parameterList1 = method.getParameterList().getParameters();
                PsiParameter[] parameterList2 = psiMethod.getParameterList().getParameters();
                if (parameterList1.length != parameterList2.length) {
                    continue;
                }
                for (int i = 0; i < parameterList1.length; i++) {
                    if (!parameterList1[i].isEquivalentTo(parameterList2[i])) {
                        isSame = false;
                        break;
                    }
                }
            }
            if (isSame) {
                psiMethod = method;
                break;
            }
        }

        // Create the new PSI method
        final PsiMethod newMethod = PsiElementFactory.getInstance(project).createMethodFromText(psiMethod.getText(), psiClass);
        PsiMethod finalPsiMethod = psiMethod;
        // If the method is the first method in the class
        if (aboveSignature == null) {
            PsiMethod psiMethodAfter = null;
            for (PsiMethod otherMethod : psiMethods) {
                if (!otherMethod.isConstructor()) {
                    psiMethodAfter = otherMethod;
                    break;
                }
            }
            PsiMethod finalPsiMethodAfter = psiMethodAfter;
            context.getPlatform().runWriteCommand(project, () -> {
                psiClass.addBefore(newMethod, finalPsiMethodAfter);
                finalPsiMethod.delete();
            });
            return;
        }

        PsiMethod psiMethodBefore = null;
        // Find which method comes before the moved method
        for (PsiMethod otherMethod : psiMethods) {
            if (otherMethod.getSignature(PsiSubstitutor.UNKNOWN).toString().equals(aboveSignature)) {
                psiMethodBefore = otherMethod;
                break;
            }
        }
        PsiMethod finalPsiMethodBefore = psiMethodBefore;
        context.getPlatform().runWriteCommand(project, () -> {
            psiClass.addAfter(newMethod, finalPsiMethodBefore);
            finalPsiMethod.delete();
        });
    }
}
