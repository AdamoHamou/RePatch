package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.AddParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.PullUpMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class AddParameterPullUpMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        AddParameterObject parameter = (AddParameterObject) parameterObject;
        PullUpMethodObject method = (PullUpMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getTargetClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject addedParameter = parameter.getParameterObject();

        // If the method was pulled up before the add parameter refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.updateParameterAtLocation(-1, addedParameter);
            ((PullUpMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);

        }

        // If the add parameter refactoring happened before the pull up method refactoring
        else if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            ((PullUpMethodObject) methodObject).setOriginalMethodSignature(originalParameterMethodSignature);
        }

    }
}
