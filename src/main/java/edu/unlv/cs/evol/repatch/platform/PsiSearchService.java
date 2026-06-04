package edu.unlv.cs.evol.repatch.platform;

import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.List;
import java.util.Objects;

/**
 * PSI lookup service carved out of {@code Utils} per Week 4 / Workstream 4B.
 *
 * Every index-backed search ({@code JavaPsiFacade.findClass},
 * {@code FilenameIndex}) runs inside
 * {@link PlatformFacade#runInSmartReadAction}, so callers never observe a
 * dumb index. This is the fix for the post-checkout
 * {@code IndexNotReadyException} flood: the pipeline reads PSI immediately
 * after git checkouts, when indexing is still in flight, and the previous
 * bare {@code findClass} calls in {@code Utils} threw on every one.
 *
 * Like {@link VfsSyncService}, all threading and index-readiness concerns
 * are delegated to {@link PlatformFacade}; this class contains no
 * {@code *.impl} imports and never touches {@code DumbService} or
 * application threading directly. The {@code com.intellij.psi} imports it
 * does keep are the PSI model and search API — they are this service's
 * domain and appear in its signatures.
 */
public final class PsiSearchService {

    private final PlatformFacade platform;

    public PsiSearchService(PlatformFacade platform) {
        this.platform = platform;
    }

    /**
     * Resolve a class by qualified name, falling back to a filename-index
     * walk when the project has no module/gradle structure for the package.
     * Formerly {@code Utils.getPsiClassFromClassAndFileNames}.
     *
     * @return the class, or {@code null} when neither lookup finds it.
     */
    public PsiClass findClass(Project project, String className, String filePath) {
        return platform.runInSmartReadAction(project, () -> {
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.allScope(project));
            // If the class isn't found, there might not have been a gradle file
            // and we need to find the class another way
            if (psiClass == null) {
                psiClass = findClassByFilePathInReadAction(project, filePath, className);
            }
            return psiClass;
        });
    }

    /**
     * Resolve a package by qualified name, waiting for the index when dumb.
     * Returns {@code null} when the package does not exist.
     */
    public PsiPackage findPackage(Project project, String qualifiedName) {
        return platform.runInSmartReadAction(project,
                () -> JavaPsiFacade.getInstance(project).findPackage(qualifiedName));
    }

    /**
     * Resolve a class by file path + qualified name via the filename index.
     * Formerly {@code Utils.getPsiClassByFilePath}.
     */
    public PsiClass findClassByFilePath(Project project, String filePath, String qualifiedClass) {
        return platform.runInSmartReadAction(project,
                () -> findClassByFilePathInReadAction(project, filePath, qualifiedClass));
    }

    /** Body of {@link #findClassByFilePath}; must run inside a smart read action. */
    private static PsiClass findClassByFilePathInReadAction(Project project, String filePath, String qualifiedClass) {
        // Get the name of the java file without the path
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        PsiFile[] psiFiles = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.allScope(project));
        // If no files are found, give an error message for debugging
        if (psiFiles.length == 0) {
            System.out.println("FAILED HERE");
            System.out.println(filePath);
            return null;
        }
        for (PsiFile file : psiFiles) {
            String classPath = file.getVirtualFile().getPath();
            if (!classPath.contains(filePath)) {
                continue;
            }
            PsiJavaFile psiFile = (PsiJavaFile) file;
            // Get the classes in the file
            PsiClass[] jClasses = psiFile.getClasses();
            for (PsiClass it : jClasses) {
                // Find the class that the refactoring happens in
                if (Objects.equals(it.getQualifiedName(), qualifiedClass)) {
                    return it;
                }
                // Need to update tests to remove this
                if (ApplicationManager.getApplication().isUnitTestMode()) {
                    if (qualifiedClass.contains(Objects.requireNonNull(it.getName()))) {
                        return it;
                    }
                }
                PsiClass[] innerClasses = it.getInnerClasses();
                for (PsiClass innerIt : innerClasses) {
                    if (Objects.equals(innerIt.getQualifiedName(), qualifiedClass)) {
                        return innerIt;
                    }
                }
            }
            for (PsiClass it : jClasses) {
                String qName = it.getQualifiedName();
                assert qName != null;
                qName = qName.substring(qName.lastIndexOf(".") + 1);
                String otherName = qualifiedClass.substring(qualifiedClass.lastIndexOf(".") + 1);
                if (Objects.equals(qName, otherName)) {
                    return it;
                }
            }
        }
        return null;
    }

    /**
     * Find the method on {@code psiClass} matching the RefactoringMiner
     * signature. Pure PSI-tree walk; no index access.
     * Formerly {@code Utils.getPsiMethod}.
     */
    public static PsiMethod findMethod(PsiClass psiClass, MethodSignatureObject methodSignatureObject) {
        PsiMethod[] methods = psiClass.getMethods();
        for (PsiMethod method : methods) {
            if (sameMethods(method, methodSignatureObject)) {
                return method;
            }
        }
        return null;
    }

    /** Formerly {@code Utils.getPsiField}. Pure PSI-tree walk. */
    public static PsiField findField(PsiClass psiClass, String fieldName) {
        PsiField[] fields = psiClass.getFields();
        for (PsiField field : fields) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    /** Formerly {@code Utils.getPsiParameter}. Pure PSI-tree walk. */
    public static PsiParameter findParameter(PsiMethod psiMethod, ParameterObject parameterObject) {
        // No need to compare types, two parameters in the same signature cannot have the same name,
        // so the type does not matter
        String parameterName = parameterObject.getName();
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        for (PsiParameter parameter : parameters) {
            String psiParameterName = parameter.getName();
            if (psiParameterName.equals(parameterName)) {
                return parameter;
            }
        }

        return null;
    }

    /**
     * Compare a PSI method against a RefactoringMiner method signature.
     * Formerly {@code Utils.ifSameMethods}.
     */
    public static boolean sameMethods(PsiMethod method, MethodSignatureObject methodSignature) {
        PsiParameter[] psiParameterList = method.getParameterList().getParameters();
        List<ParameterObject> parameters = methodSignature.getParameterList();
        String umlName = methodSignature.getName();
        String psiName = method.getName();
        int firstUMLParam = 0;
        // Check if the method names are the same
        if (!umlName.equals(psiName)) {
            return false;
        }
        // If the number of parameters are different, the methods are different
        // Subtract 1 from umlParameters because umlParameters includes return type
        if (!methodSignature.isConstructor()) {
            if (parameters.size() - 1 != psiParameterList.length) {
                return false;
            }
            PsiType psiReturnType = method.getReturnType();
            assert psiReturnType != null;
            String psiType = psiReturnType.getPresentableText();
            ParameterObject parameterObject = parameters.get(0);
            String parameterType = parameterObject.getType();
            // Check if the return types are the same
            if (!psiType.equals(parameterType)) {
                // Check if UML type is class type
                if (parameterType.contains(".")) {
                    parameterType = parameterType.substring(parameterType.lastIndexOf(".") + 1);
                    if (!parameterType.equals(psiType)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            firstUMLParam = 1;
        } else {
            if (parameters.size() != psiParameterList.length) {
                return false;
            }
        }
        // Check if the parameters are the same
        return parameterComparator(firstUMLParam, parameters, psiParameterList);
    }

    /*
     * Compare the parameters in the UML parameter list to the parameters in the PSI parameter list to see if
     * the method signatures are the same.
     */
    private static boolean parameterComparator(int firstUMLParam, List<ParameterObject> parameters,
                                               PsiParameter[] psiParameterList) {
        ParameterObject parameterObject;
        String umlType;
        String psiType;
        // Check if the parameters are the same
        for (int i = firstUMLParam; i < parameters.size(); i++) {
            int j = i - firstUMLParam;
            parameterObject = parameters.get(i);
            PsiParameter psiParameter = psiParameterList[j];
            umlType = parameterObject.getType();

            String parameterName = psiParameter.getName();
            psiType = psiParameter.getText();
            psiType = psiType.substring(0, psiType.lastIndexOf(parameterName) - 1);
            // If the parameter has the final modifier, remove it for comparison with UML parameter.
            if (psiParameter.hasModifierProperty(PsiModifier.FINAL)) {
                psiType = psiType.substring(psiType.indexOf("final ") + 6);
            }
            // Replace int... with int[] for comparison with RefMiner object
            if (psiType.contains("...")) {
                psiType = psiType.replace("...", "[]");
            }
            if (!umlType.equals(psiType)) {
                return false;
            }

        }
        return true;
    }
}
