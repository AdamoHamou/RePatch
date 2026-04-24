package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.ExtractMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.ReorderParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

import java.util.List;

public class ReorderParameterExtractMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        ReorderParameterObject parameter = (ReorderParameterObject) parameterObject;
        ExtractMethodObject method = (ExtractMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClassName();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getDestinationClassName();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        List<ParameterObject> reorderedParameters = parameter.getReorderedParameterList();

        // If the source method's parameters are reordered
        if(destinationMethodClass.equals(originalParameterClass)
                && originalMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            originalMethodSignature.replaceParameterList(reorderedParameters);
            ((ExtractMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the extracted method's parameters are reordered
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.replaceParameterList(reorderedParameters);
            ((ExtractMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }

}
