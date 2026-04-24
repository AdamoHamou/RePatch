package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.ChangeParameterTypeObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class ChangeParameterTypePushDownMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        ChangeParameterTypeObject parameter = (ChangeParameterTypeObject) parameterObject;
        PushDownMethodObject method = (PushDownMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getTargetBaseClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject originalParameter = parameter.getOriginalParameter();
        ParameterObject destinationParameter = parameter.getDestinationParameter();

        // If the method was pushed down before the change parameter type refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = destinationParameterMethodSignature.getParameterLocation(destinationParameter);
            destinationMethodSignature.updateParameterAtLocation(location, destinationParameter);
            ((PushDownMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);

        }

        // If the change parameter type refactoring happened before the push up method refactoring
        else if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            int location = originalParameterMethodSignature.getParameterLocation(originalParameter);
            originalMethodSignature.updateParameterAtLocation(location, originalParameter);
            ((PushDownMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);
        }

    }
}
