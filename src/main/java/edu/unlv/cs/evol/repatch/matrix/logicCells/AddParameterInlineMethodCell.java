package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.AddParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class AddParameterInlineMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        AddParameterObject parameter = (AddParameterObject) parameterObject;
        InlineMethodObject method = (InlineMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClassName();
        String destinationParameterClass = parameter.getDestinationClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject addedParameter = parameter.getParameterObject();

        // If the add parameter refactoring happens in the inlined method
        if(originalMethodClass.equals(originalParameterClass)
                && originalMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            originalMethodSignature.updateParameterAtLocation(-1, addedParameter);
            ((InlineMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);

        }

        // If the add parameter refactoring happens in the target method
        else if(originalMethodClass.equals(destinationParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.updateParameterAtLocation(-1, addedParameter);
            ((InlineMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

    }

}
