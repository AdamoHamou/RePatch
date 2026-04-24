package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RemoveParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

public class RemoveParameterMoveRenameMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        RemoveParameterObject parameter = (RemoveParameterObject) parameterObject;
        MoveRenameMethodObject method = (MoveRenameMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClassName();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getDestinationClassName();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        ParameterObject removedParameter = parameter.getRemovedParameterObject();

        // If the method was renamed and/or moved before the remove parameter refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            int location = destinationMethodSignature.getParameterLocation(removedParameter);
            destinationMethodSignature.removeParameterAtLocation(location);
            ((MoveRenameMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);

        }

        // If the remove parameter refactoring happened before the move + rename method refactoring
        else if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            // Get location of parameter before it was removed
            int location = originalParameterMethodSignature.getParameterLocation(removedParameter);
            originalMethodSignature.addParameterAtLocation(location, removedParameter);
            ((MoveRenameMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);
        }

    }

}
