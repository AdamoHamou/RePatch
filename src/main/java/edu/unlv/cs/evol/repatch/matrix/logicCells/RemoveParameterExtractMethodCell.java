package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.ExtractMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RemoveParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class RemoveParameterExtractMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        RemoveParameterObject parameter = (RemoveParameterObject) parameterObject;
        ExtractMethodObject method = (ExtractMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String originalMethodClass = method.getOriginalClassName();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject removedParameter = parameter.getRemovedParameterObject();

        // If the remove parameter refactoring happens in the original method
        if(originalMethodClass.equals(originalParameterClass)
                && originalMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = originalMethodSignature.getParameterLocation(removedParameter);
            originalMethodSignature.removeParameterAtLocation(location);
            ((ExtractMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the remove parameter refactoring happens in the extracted method
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = destinationMethodSignature.getParameterLocation(removedParameter);
            destinationMethodSignature.removeParameterAtLocation(location);
            ((ExtractMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }

}
