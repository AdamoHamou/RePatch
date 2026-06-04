package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.ExtractMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.changeSignature.JavaThrownExceptionInfo;
import com.intellij.refactoring.changeSignature.ThrownExceptionInfo;
import com.intellij.refactoring.inline.InlineMethodProcessor;
import com.intellij.util.Query;
import gr.uom.java.xmi.decomposition.OperationInvocation;

/**
 * Inverts an extract method refactoring by inlining the extracted method.
 * Migrated onto the Week 4A contract.
 *
 * Operation family: extract/inline.
 *  - Expected PSI inputs: the class containing both the source and the
 *    extracted method, resolvable from the original file path; the
 *    extracted method's invocation site inside the source method.
 *  - Side effects: the extracted method is inlined at its call sites and
 *    its declaration removed; the surrounding statements and thrown
 *    exceptions are recorded on the refactoring object so the replay can
 *    re-extract the same region.
 *  - Failure behavior: unresolvable class/method/invocation-surroundings
 *    classify as PRECONDITION_FAILED before mutation; processor blowups
 *    are PROCESSOR_THREW.
 *  - Postcondition: the extracted method is no longer present on the
 *    class.
 */
public class InvertExtractMethod implements RefactoringOperation {

    private final ExtractMethodObject extractMethodObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiMethod extractedMethod;
    private PsiJavaCodeReferenceElement referenceElement;

    public InvertExtractMethod(RefactoringObject refactoringObject) {
        this.extractMethodObject = (ExtractMethodObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "invert " + extractMethodObject.getRefactoringType()
                + " (" + extractMethodObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed {
        Project project = context.getProject();
        MethodSignatureObject originalMethod = extractMethodObject.getOriginalMethodSignature();
        MethodSignatureObject destinationMethod = extractMethodObject.getDestinationMethodSignature();

        // Get PSI Method using extractedOperation data
        String className = extractMethodObject.getOriginalClassName();
        String filePath = extractMethodObject.getOriginalFilePath();
        context.getProjectRoots().addSourceRoot(project, filePath, className);
        psiClass = context.getPsiSearch().findClass(project, className, filePath);
        if (psiClass == null) {
            throw new PreconditionFailed("class " + className + " not resolvable from " + filePath);
        }
        extractedMethod = PsiSearchService.findMethod(psiClass, destinationMethod);
        if (extractedMethod == null) {
            throw new PreconditionFailed("extracted method " + destinationMethod.getName()
                    + " not found on " + className);
        }
        PsiMethod psiMethod = PsiSearchService.findMethod(psiClass, originalMethod);
        if (psiMethod == null) {
            throw new PreconditionFailed("source method " + originalMethod.getName()
                    + " not found on " + className);
        }
        if (extractMethodObject.getMethodInvocations().isEmpty()) {
            throw new PreconditionFailed("no recorded invocations of " + destinationMethod.getName());
        }
        // Get the first method invocation
        OperationInvocation methodInvocation = extractMethodObject.getMethodInvocations().get(0);

        ThrownExceptionInfo[] thrownExceptionInfo = getThrownExceptionInfo(extractedMethod);

        // Get the statements that surround the method invocation. The
        // reference search is index-backed, so run it in a smart read action.
        PsiElement[] surroundingElements = context.getIndexing().computeInSmartMode(project,
                () -> getSurroundingElements(psiMethod, extractedMethod, methodInvocation));
        if (surroundingElements == null) {
            throw new PreconditionFailed("invocation of " + destinationMethod.getName()
                    + " not found inside " + originalMethod.getName());
        }
        if (surroundingElements[0] == null || surroundingElements[1] == null) {
            throw new PreconditionFailed("surrounding statements of the " + destinationMethod.getName()
                    + " invocation could not be determined");
        }
        extractMethodObject.setThrownExceptionInfo(thrownExceptionInfo);
        SmartPsiElementPointer[] surroundingPointers = new SmartPsiElementPointer[2];
        surroundingPointers[0] = SmartPointerManager.createPointer(surroundingElements[0]);
        surroundingPointers[1] = SmartPointerManager.createPointer(surroundingElements[1]);
        extractMethodObject.setSurroundingElements(surroundingPointers);

        // Usage lookup is index-backed as well; null is a legitimate result
        // (the processor then inlines every reference).
        referenceElement = context.getIndexing().computeInSmartMode(project,
                () -> Utils.getPsiReferenceExpressionsForExtractMethod(extractedMethod, project));
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        Editor editor = context.getPlatform().getSelectedTextEditor(project);
        InlineMethodProcessor inlineMethodProcessor = new InlineMethodProcessor(project, extractedMethod,
                referenceElement, editor, false);
        context.getPlatform().invokeAndWait(inlineMethodProcessor);

        context.getPlatform().closeActiveUsageView(project);

        VirtualFile vFile = psiClass.getContainingFile().getVirtualFile();
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        MethodSignatureObject destinationMethod = extractMethodObject.getDestinationMethodSignature();
        String className = extractMethodObject.getOriginalClassName();
        String filePath = extractMethodObject.getOriginalFilePath();
        PsiClass freshClass = context.getPsiSearch().findClass(context.getProject(), className, filePath);
        if (freshClass == null) {
            return "class " + className + " not resolvable after inline";
        }
        if (PsiSearchService.findMethod(freshClass, destinationMethod) != null) {
            return "extracted method " + destinationMethod.getName() + " still present on " + className;
        }
        return null;
    }

    /*
     * Get the method invocation and the PSI Elements before and after the method invocation so we can extract the
     * correct code when we replay.
     */
    private PsiElement[] getSurroundingElements(PsiMethod psiMethod, PsiMethod extractedMethod, OperationInvocation methodInvocation) {
        PsiElement[] surroundingElements = new PsiElement[4];
        PsiCodeBlock psiCodeBlock = psiMethod.getBody();
        if (psiCodeBlock == null) {
            return null;
        }

        // Get the correct PSI reference
        Query<PsiReference> psiReferences = ReferencesSearch.search(extractedMethod);
        if (psiReferences.findFirst() == null) {
            return null;
        }
        PsiElement psiElement = psiReferences.findFirst().getElement();

        if (psiElement instanceof PsiMethod) {
            for (PsiReference psiReference : psiReferences) {
                psiElement = (PsiElement) psiReference;
                if (psiElement instanceof PsiMethod) {
                    continue;
                }
                PsiElement containingMethod = PsiTreeUtil.getParentOfType(psiElement, PsiMethod.class);
                assert containingMethod != null;
                if (containingMethod.isEquivalentTo(psiMethod)) {
                    break;
                }
            }
        }
        PsiElement psiParent = PsiTreeUtil.getParentOfType(psiElement, PsiDeclarationStatement.class, PsiExpressionStatement.class);
        PsiElement prevSibling;
        PsiElement nextSibling;
        if (psiParent == null) {
            psiParent = PsiTreeUtil.getParentOfType(psiElement, PsiMethodCallExpression.class);
            if (psiParent == null) {
                return null;
            }
            PsiElement candidateElement = psiParent.getParent();
            if (candidateElement instanceof PsiReturnStatement) {
                psiParent = candidateElement;
            }
        }
        if (psiParent.getPrevSibling() == null) {
            prevSibling = psiParent;
        } else {
            prevSibling = psiParent.getPrevSibling();
        }
        if (psiParent.getNextSibling() == null) {
            nextSibling = psiParent;
        } else {
            nextSibling = psiParent.getNextSibling();
        }
        if (prevSibling instanceof PsiWhiteSpace) {
            prevSibling = prevSibling.getPrevSibling();
        }
        // Handle when previous sibling is the start of the code block
        surroundingElements[0] = prevSibling;
        if (nextSibling instanceof PsiWhiteSpace) {
            nextSibling = nextSibling.getNextSibling();
        }
        // Handle case when next sibling is the end of the code block
        surroundingElements[1] = nextSibling;
        return surroundingElements;
    }

    private ThrownExceptionInfo[] getThrownExceptionInfo(PsiMethod psiMethod) {
        int size = psiMethod.getThrowsTypes().length;
        ThrownExceptionInfo[] thrownExceptionInfos = new ThrownExceptionInfo[size];
        for (int i = 0; i < size; i++) {
            JavaThrownExceptionInfo thrownExceptionInfo = new JavaThrownExceptionInfo(i);
            thrownExceptionInfo.updateFromMethod(psiMethod);
            thrownExceptionInfos[i] = thrownExceptionInfo;
        }
        return thrownExceptionInfos;
    }
}
