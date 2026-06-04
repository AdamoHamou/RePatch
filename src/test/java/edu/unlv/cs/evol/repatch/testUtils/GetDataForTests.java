package edu.unlv.cs.evol.repatch.testUtils;

import gr.uom.java.xmi.UMLModel;
import gr.uom.java.xmi.UMLModelASTReader;
import gr.uom.java.xmi.diff.UMLModelDiff;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringMinerTimedOutException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects refactorings between two fixture directories with
 * RefactoringMiner, exactly the way the pipeline's detection step does.
 * Ported from the upstream RefMerge test utilities.
 */
public class GetDataForTests {

    public static List<Refactoring> getRefactorings(String type, String originalPath, String refactoredPath) {
        List<Refactoring> refs = new ArrayList<>();
        try {
            UMLModel model1 = new UMLModelASTReader(new File(originalPath)).getUmlModel();
            UMLModel model2 = new UMLModelASTReader(new File(refactoredPath)).getUmlModel();
            UMLModelDiff modelDiff = model1.diff(model2);
            List<Refactoring> refactorings = modelDiff.getRefactorings();
            if (refactorings == null) {
                return null;
            }
            for (Refactoring ref : refactorings) {
                if (ref.getRefactoringType().toString().equals(type)) {
                    refs.add(ref);
                }
            }
            return refs;
        } catch (IOException | RefactoringMinerTimedOutException e) {
            System.out.println("Error: Problem getting refactoring operations");
            e.printStackTrace();
        }
        return null;
    }
}
