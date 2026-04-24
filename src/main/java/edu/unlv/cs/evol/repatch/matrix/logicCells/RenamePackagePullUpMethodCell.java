package edu.unlv.cs.evol.repatch.matrix.logicCells;


import edu.unlv.cs.evol.repatch.refactoringObjects.PullUpMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;

public class RenamePackagePullUpMethodCell {

    public static void checkCombination(RefactoringObject dispatcher, RefactoringObject receiver) {
        PullUpMethodObject dispatcherObject = (PullUpMethodObject) dispatcher;
        RenamePackageObject receiverObject = (RenamePackageObject) receiver;

        String dispatcherOriginalClassName = dispatcherObject.getOriginalClass();
        String dispatcherRefactoredClassName = dispatcherObject.getTargetClass();
        String receiverOriginalPackageName = receiverObject.getOriginalName();
        String receiverDestinationPackageName = receiverObject.getDestinationName();

        // Need to check both cases
        // If the source method's package is renamed after the pull up method refactoring
        if(dispatcherOriginalClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherOriginalClassName.lastIndexOf("."));
            // Update the classes package
            ((PullUpMethodObject) dispatcher).setOriginalClass(receiverDestinationPackageName + refactoredClassName);
        }

        // If the pulled up method's package is renamed after the pull up method refactoring
        if(dispatcherRefactoredClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherRefactoredClassName.lastIndexOf("."));
            // Update the classes package
            ((PullUpMethodObject) dispatcher).setTargetClass(receiverDestinationPackageName + refactoredClassName);
        }
    }

}
