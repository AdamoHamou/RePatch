package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.PullUpMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RemoveParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class RemoveParameterPullUpMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        RemoveParameterObject parameter = (RemoveParameterObject) parameterObject;
        PullUpMethodObject method = (PullUpMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getTargetClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject removedParameter = parameter.getRemovedParameterObject();

        // If the method was pulled up before the remove parameter refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = destinationMethodSignature.getParameterLocation(removedParameter);
            destinationMethodSignature.removeParameterAtLocation(location);
            ((PullUpMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);

        }

        // If the remove parameter refactoring happened before the pull up method refactoring
        else if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            int location = originalParameterMethodSignature.getParameterLocation(removedParameter);
            originalMethodSignature.addParameterAtLocation(location, removedParameter);
            ((PullUpMethodObject) methodObject).setOriginalMethodSignature(originalParameterMethodSignature);
        }

    }
}
