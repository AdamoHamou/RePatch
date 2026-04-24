package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenameParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class RenameParameterPushDownMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        PushDownMethodObject method = (PushDownMethodObject) methodObject;
        RenameParameterObject parameter = (RenameParameterObject) parameterObject;

        String originalMethodClass = method.getOriginalClass();
        String destinationMethodClass = method.getTargetBaseClass();
        String originalParameterClass = parameter.getOriginalClassName();
        String destinationParameterClass = parameter.getRefactoredClassName();

        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();
        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getRefactoredMethodSignature();

        ParameterObject originalParameter = parameter.getOriginalParameterObject();
        ParameterObject destinationParameter = parameter.getRefactoredParameterObject();

        // If the rename parameter refactoring happens after the push down method refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignatureExcludingParameterNames(originalParameterMethodSignature)) {
            // Update original parameter
            originalParameterMethodSignature.setName(originalMethodSignature.getName());
            ((RenameParameterObject) parameterObject).setOriginalMethodSignature(originalParameterMethodSignature);
            ((RenameParameterObject) parameterObject).setOriginalClassName(originalMethodClass);
            // Update refactored method
            int location = destinationParameterMethodSignature.getParameterLocation(destinationParameter);
            destinationMethodSignature.updateParameterAtLocation(location, destinationParameter);
            ((PushDownMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);
        }

        // If the rename parameter refactoring happens before the push down method refactoring
        if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            // Update refactored parameter
            destinationParameterMethodSignature.setName(destinationMethodSignature.getName());
            ((RenameParameterObject) parameterObject).setRefactoredMethodSignature(destinationParameterMethodSignature);
            ((RenameParameterObject) parameterObject).setRefactoredClassName(destinationMethodClass);
            // Update original method
            int location = originalParameterMethodSignature.getParameterLocation(originalParameter);
            originalMethodSignature.updateParameterAtLocation(location, originalParameter);
            ((PushDownMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);
        }
    }

}
