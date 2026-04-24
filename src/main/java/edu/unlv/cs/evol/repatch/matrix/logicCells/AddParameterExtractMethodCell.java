package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.AddParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.ExtractMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class AddParameterExtractMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        AddParameterObject parameter = (AddParameterObject) parameterObject;
        ExtractMethodObject method = (ExtractMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String originalMethodClass = method.getOriginalClassName();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject addedParameter = parameter.getParameterObject();

        // If the add parameter refactoring happens in the source method
        if(originalMethodClass.equals(originalParameterClass)
                && originalMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            originalMethodSignature.updateParameterAtLocation(-1, addedParameter);
            ((ExtractMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the add parameter refactoring happens in the extracted method
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.updateParameterAtLocation(-1, addedParameter);
            ((ExtractMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }

}
