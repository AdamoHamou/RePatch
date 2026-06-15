package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.extractMethod.ExtractMethodHandler;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import gr.uom.java.xmi.decomposition.AbstractCodeFragment;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Inverts an inline-method refactoring by re-extracting the inlined body into
 * a method. Migrated onto the Week 4A contract.
 *
 * Operation family: extract/inline (method).
 *  - Expected PSI inputs: the target method (the one the original method was
 *    inlined into) resolvable from its class + file, and the inlined code
 *    fragments locatable inside it as a contiguous statement range.
 *  - Side effects: the inlined statements are extracted back into a method
 *    named after the original; the stored original signature is updated to
 *    match the re-extracted method (replay depends on this).
 *  - Failure behavior: missing class/method classify as PRECONDITION_FAILED;
 *    fragments that cannot be located, or that do not form a contiguous
 *    sibling range, classify as UNSUPPORTED_SHAPE — before this migration the
 *    range case threw a raw {@code IllegalArgumentException: Invalid range}
 *    from {@code PsiTreeUtil.getElementsOfRange} (the 12660 chain-head throw),
 *    and the not-located case silently returned (counted as success).
 *  - Postcondition: a method with the extracted name is present on the class.
 */
public class InvertInlineMethod implements RefactoringOperation {

    private final InlineMethodObject inlineMethodObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private PsiClass psiClass;
    private PsiMethod psiMethod;
    private PsiElement[] psiElements;
    private PsiType forcedReturnType;
    private String extractedMethodName;
    private String targetOperationName;
    private String visibility;
    private VirtualFile vFile;

    public InvertInlineMethod(RefactoringObject refactoringObject) {
        this.inlineMethodObject = (InlineMethodObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "invert " + inlineMethodObject.getRefactoringType()
                + " (" + inlineMethodObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed, UnsupportedShape {
        Project project = context.getProject();
        MethodSignatureObject originalOperation = inlineMethodObject.getOriginalMethodSignature();
        MethodSignatureObject targetOperation = inlineMethodObject.getDestinationMethodSignature();
        String targetOperationClassName = inlineMethodObject.getDestinationClassName();
        String filePath = inlineMethodObject.getDestinationFilePath();

        psiClass = context.getPsiSearch().findClass(project, targetOperationClassName, filePath);
        if (psiClass == null) {
            throw new PreconditionFailed("class " + targetOperationClassName
                    + " not resolvable from " + filePath);
        }
        vFile = psiClass.getContainingFile().getVirtualFile();

        psiMethod = PsiSearchService.findMethod(psiClass, targetOperation);
        if (psiMethod == null) {
            throw new PreconditionFailed("target method " + targetOperation.getName()
                    + " not found on " + targetOperationClassName);
        }

        extractedMethodName = originalOperation.getName();
        targetOperationName = targetOperation.getName();
        visibility = originalOperation.getVisibility();
        forcedReturnType = getPsiReturnType(originalOperation, psiMethod);

        Set<AbstractCodeFragment> inlinedCodeFragments = inlineMethodObject.getInlinedCodeFragments();
        psiElements = getElementsFromTargetOperationFragments(psiMethod, inlinedCodeFragments);
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();
        Editor editor = context.getPlatform().getSelectedTextEditor(project);
        String helpId = "Undo inline method";

        ExtractMethodProcessor extractMethodProcessor = new ExtractMethodProcessor(project, editor, psiElements,
                forcedReturnType, extractedMethodName, targetOperationName, helpId);
        extractMethodProcessor.setMethodName(extractedMethodName);
        extractMethodProcessor.setMethodVisibility(visibility);
        try {
            extractMethodProcessor.prepare();
        } catch (PrepareFailedException e) {
            e.printStackTrace();
        }
        extractMethodProcessor.setDataFromInputVariables();
        ExtractMethodHandler.extractMethod(project, extractMethodProcessor);

        // The method signature could change if code was added/deleted after inlining it
        PsiMethod extractedMethod = extractMethodProcessor.getExtractedMethod();
        updateMethodSignature(inlineMethodObject, extractedMethod);

        context.getPlatform().closeActiveUsageView(project);
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        PsiClass cls = context.getPsiSearch().findClass(context.getProject(),
                inlineMethodObject.getDestinationClassName(), inlineMethodObject.getDestinationFilePath());
        if (cls == null) {
            return "class " + inlineMethodObject.getDestinationClassName() + " not resolvable after extract";
        }
        for (PsiMethod m : cls.getMethods()) {
            if (m.getName().equals(extractedMethodName)) {
                return null;
            }
        }
        return "extracted method " + extractedMethodName + " not present on "
                + inlineMethodObject.getDestinationClassName();
    }

    /*
     * Gets the return type of the original method.
     */
    private PsiType getPsiReturnType(MethodSignatureObject methodSignature, PsiMethod psiMethod) {
        ParameterObject returnParameter = methodSignature.getReturnParameter();
        String parameterType = returnParameter.getType();
        PsiElementFactory factory = PsiElementFactory.getInstance(psiMethod.getProject());
        return factory.createTypeFromText(parameterType, psiMethod);
    }

    /*
     * Get the PSI elements to be extracted from the inlined code fragments.
     * Throws UnsupportedShape when the fragments cannot be located or do not
     * form a contiguous sibling range.
     */
    private PsiElement[] getElementsFromTargetOperationFragments(PsiMethod psiMethod,
                                                                 Set<AbstractCodeFragment> codeFragments)
            throws UnsupportedShape {
        Iterator<AbstractCodeFragment> iterator = codeFragments.iterator();
        AbstractCodeFragment firstFragment = iterator.next();
        AbstractCodeFragment lastFragment = firstFragment;
        for (AbstractCodeFragment codeFragment : codeFragments) {
            lastFragment = codeFragment;
        }
        PsiElement first = getPsiElementFromCodeFragment(firstFragment, psiMethod);
        PsiElement last = getPsiElementFromCodeFragment(lastFragment, psiMethod);
        if (first == null || last == null) {
            throw new UnsupportedShape("inlined code fragment not located inside "
                    + psiMethod.getName());
        }
        // The fragments can resolve to elements at different tree depths (e.g. a
        // nested reference expression vs. a top-level statement), which
        // PsiTreeUtil.getElementsOfRange rejects with "Invalid range" (the
        // 12660 chain-head throw). Normalize both endpoints to the ancestor that
        // is a direct child of their common parent, then order them. For inputs
        // that already form a valid range this is a no-op, so currently-working
        // inversions are unaffected.
        PsiElement[] range = normalizeToSiblingRange(first, last);
        if (range == null) {
            throw new UnsupportedShape("inlined fragments do not form a contiguous "
                    + "statement range inside " + psiMethod.getName());
        }
        List<PsiElement> psiElements = PsiTreeUtil.getElementsOfRange(range[0], range[1]);
        return psiElements.toArray(new PsiElement[0]);
    }

    /*
     * Lift first/last to a common-parent sibling pair, ordered start..end.
     * Returns null when no usable range exists. Package-private + static so the
     * 12660 "Invalid range" regression can be unit-tested against constructed PSI.
     */
    static PsiElement[] normalizeToSiblingRange(PsiElement first, PsiElement last) {
        PsiElement common = PsiTreeUtil.findCommonParent(first, last);
        if (common == null) {
            return null;
        }
        // One endpoint contains the other: the containing element is the range.
        if (common == first) {
            return new PsiElement[]{first, first};
        }
        if (common == last) {
            return new PsiElement[]{last, last};
        }
        PsiElement start = liftToChildOf(first, common);
        PsiElement end = liftToChildOf(last, common);
        if (start == null || end == null) {
            return null;
        }
        if (start == end) {
            return new PsiElement[]{start, end};
        }
        // Order them among the common parent's children.
        for (PsiElement e = start; e != null; e = e.getNextSibling()) {
            if (e == end) {
                return new PsiElement[]{start, end};
            }
        }
        for (PsiElement e = end; e != null; e = e.getNextSibling()) {
            if (e == start) {
                return new PsiElement[]{end, start};
            }
        }
        return null;
    }

    /*
     * Walk element up until its parent is the given parent; null if not a descendant.
     */
    private static PsiElement liftToChildOf(PsiElement element, PsiElement parent) {
        PsiElement current = element;
        while (current != null && current.getParent() != parent) {
            current = current.getParent();
        }
        return current;
    }

    /*
     * Use the code fragment to get the PSI element inside of the method
     */
    private PsiElement getPsiElementFromCodeFragment(final AbstractCodeFragment inlinedFragment,
                                                     PsiMethod psiMethod) {
        final PsiElement[] psiElement = new PsiElement[1];
        final String sourceText = Utils.formatText(inlinedFragment.getString());
        psiMethod.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                if (psiElement[0] != null) {
                    return;
                }
                String elementsText = Utils.formatText(element.getText());
                if (sourceText.equals(elementsText)) {
                    psiElement[0] = element;
                }
            }
        });
        return psiElement[0];
    }

    /*
     * Update the stored original method signature to match the extracted method.
     */
    private void updateMethodSignature(RefactoringObject refactoringObject, PsiMethod psiMethod) {
        PsiParameterList psiParameterList = psiMethod.getParameterList();
        PsiParameter[] psiParameters = psiParameterList.getParameters();
        List<ParameterObject> parameterObjects = new ArrayList<>();
        ParameterObject returnParameter = ((InlineMethodObject) refactoringObject).getOriginalMethodSignature().getReturnParameter();
        parameterObjects.add(returnParameter);
        for (PsiParameter psiParameter : psiParameters) {
            String parameterType = psiParameter.getText();
            String parameterName = psiParameter.getName();
            ParameterObject parameterObject = new ParameterObject(parameterType, parameterName);
            parameterObjects.add(parameterObject);
        }
        String methodName = psiMethod.getName();
        MethodSignatureObject methodSignature = new MethodSignatureObject(parameterObjects, methodName);
        ((InlineMethodObject) refactoringObject).setOriginalMethodSignature(methodSignature);
    }
}
