package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;

public class RenamePackageMoveRenameMethodCell {

    public static void checkCombination(RefactoringObject dispatcher, RefactoringObject receiver) {
        MoveRenameMethodObject dispatcherObject = (MoveRenameMethodObject) dispatcher;
        RenamePackageObject receiverObject = (RenamePackageObject) receiver;

        String dispatcherRefactoredClassName = dispatcherObject.getDestinationClassName();
        String receiverOriginalPackageName = receiverObject.getOriginalName();
        String receiverDestinationPackageName = receiverObject.getDestinationName();

        // If c1 -> c2 before p1.c2 -> p2.c2
        if(dispatcherRefactoredClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherRefactoredClassName.lastIndexOf("."));
            // Update the classes package
            ((MoveRenameMethodObject) dispatcher).setDestinationClassName(receiverDestinationPackageName + refactoredClassName);
        }
    }

}
