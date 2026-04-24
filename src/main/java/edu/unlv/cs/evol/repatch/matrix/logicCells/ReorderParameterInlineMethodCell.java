package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.ReorderParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

import java.util.List;

public class ReorderParameterInlineMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        ReorderParameterObject parameter = (ReorderParameterObject) parameterObject;
        InlineMethodObject method = (InlineMethodObject) methodObject;

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
            ((InlineMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the target method's parameters are reordered
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.replaceParameterList(reorderedParameters);
            ((InlineMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }
}
