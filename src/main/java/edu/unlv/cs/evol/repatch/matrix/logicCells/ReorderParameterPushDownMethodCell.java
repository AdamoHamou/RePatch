package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.ReorderParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;

import java.util.List;

public class ReorderParameterPushDownMethodCell {

    public static void checkCombination(RefactoringObject methodObject, RefactoringObject parameterObject) {
        ReorderParameterObject parameter = (ReorderParameterObject) parameterObject;
        PushDownMethodObject method = (PushDownMethodObject) methodObject;

        String originalParameterClass = parameter.getOriginalClass();
        String originalMethodClass = method.getOriginalClass();
        String destinationParameterClass = parameter.getDestinationClass();
        String destinationMethodClass = method.getTargetBaseClass();

        MethodSignatureObject originalParameterMethodSignature = parameter.getOriginalMethod();
        MethodSignatureObject originalMethodSignature = method.getOriginalMethodSignature();
        MethodSignatureObject destinationParameterMethodSignature = parameter.getDestinationMethod();
        MethodSignatureObject destinationMethodSignature = method.getDestinationMethodSignature();

        List<ParameterObject> originalParameters = parameter.getOriginalParameterList();
        List<ParameterObject> reorderedParameters = parameter.getReorderedParameterList();

        // If the method was pushed down before the reorder parameters refactoring
        if(destinationMethodClass.equals(originalParameterClass)
                && destinationMethodSignature.equalsSignature(originalParameterMethodSignature)) {
            destinationMethodSignature.replaceParameterList(reorderedParameters);
            ((PushDownMethodObject) methodObject).setDestinationMethodSignature(destinationMethodSignature);

        }

        // If the reorder parameters refactoring happened before the push down method refactoring
        else if(originalMethodClass.equals(destinationParameterClass)
                && originalMethodSignature.equalsSignature(destinationParameterMethodSignature)) {
            originalMethodSignature.replaceParameterList(originalParameters);
            ((PushDownMethodObject) methodObject).setOriginalMethodSignature(originalMethodSignature);
        }

    }
}
