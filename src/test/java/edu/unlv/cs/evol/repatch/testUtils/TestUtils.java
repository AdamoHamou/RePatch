package edu.unlv.cs.evol.repatch.testUtils;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PSI helpers for fixture-based operation tests. Ported from the upstream
 * RefMerge test utilities.
 */
public class TestUtils {

    public static List<String> getMethodNames(PsiMethod[] methods) {
        ArrayList<String> names = new ArrayList<>();
        for (PsiMethod method : methods) {
            names.add(method.getName());
        }
        return names;
    }

    public static PsiMethod[] getPsiMethodsFromFile(PsiFile psiFile) {
        ArrayList<PsiMethod> psiMethods = new ArrayList<>();
        for (PsiClass psiClass : getPsiClassesFromFile(psiFile)) {
            psiMethods.addAll(Arrays.asList(psiClass.getMethods()));
        }
        return psiMethods.toArray(new PsiMethod[0]);
    }

    public static PsiClass[] getPsiClassesFromFile(PsiFile psiFile) {
        ArrayList<PsiClass> psiClasses = new ArrayList<>();
        PsiJavaFile psiJavaFile = (PsiJavaFile) psiFile;
        for (PsiClass psiClass : psiJavaFile.getClasses()) {
            psiClasses.add(psiClass);
            psiClasses.addAll(Arrays.asList(psiClass.getInnerClasses()));
        }
        return psiClasses.toArray(new PsiClass[0]);
    }
}
