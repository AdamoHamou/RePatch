package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RemoveParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class RemoveParameterInlineMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        RemoveParameterObject parameter = (RemoveParameterObject) parameterObject;
        InlineMethodObject method = (InlineMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClassName();
        String destinationParameterClass = parameter.getDestinationClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject removedParameter = parameter.getRemovedParameterObject();

        // If the remove parameter refactoring happens in the inlined method
        if(originalMethodClass.equals(originalParameterClass)
                && originalMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = originalMethodSignature.getParameterLocation(removedParameter);
            originalMethodSignature.removeParameterAtLocation(location);
            ((InlineMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the remove parameter refactoring happens in the target method
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = destinationMethodSignature.getParameterLocation(removedParameter);
            destinationMethodSignature.removeParameterAtLocation(location);
            ((InlineMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }
}
