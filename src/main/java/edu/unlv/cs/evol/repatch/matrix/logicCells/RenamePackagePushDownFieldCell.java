package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownFieldObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;

public class RenamePackagePushDownFieldCell {

    public static void checkCombination(RefactoringObject dispatcher, RefactoringObject receiver) {
        PushDownFieldObject dispatcherObject = (PushDownFieldObject) dispatcher;
        RenamePackageObject receiverObject = (RenamePackageObject) receiver;

        String dispatcherOriginalClassName = dispatcherObject.getOriginalClass();
        String dispatcherRefactoredClassName = dispatcherObject.getTargetSubClass();
        String receiverOriginalPackageName = receiverObject.getOriginalName();
        String receiverDestinationPackageName = receiverObject.getDestinationName();

        // Need to check both cases
        // If the source method's package is renamed after the push down method refactoring
        if(dispatcherOriginalClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherOriginalClassName.lastIndexOf("."));
            // Update the classes package
            ((PushDownFieldObject) dispatcher).setOriginalClass(receiverDestinationPackageName + refactoredClassName);
        }

        // If the pulled up method's package is renamed after the push down method refactoring
        if(dispatcherRefactoredClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherRefactoredClassName.lastIndexOf("."));
            // Update the classes package
            ((PushDownFieldObject) dispatcher).setTargetSubClass(receiverDestinationPackageName + refactoredClassName);
        }
    }

}
