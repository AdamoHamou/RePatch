package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;

public class RenamePackageInlineMethodCell {

    public static void checkCombination(RefactoringObject dispatcher, RefactoringObject receiver) {
        InlineMethodObject dispatcherObject = (InlineMethodObject) dispatcher;
        RenamePackageObject receiverObject = (RenamePackageObject) receiver;

        String dispatcherOriginalClassName = dispatcherObject.getOriginalClassName();
        String dispatcherRefactoredClassName = dispatcherObject.getDestinationClassName();
        String receiverOriginalPackageName = receiverObject.getOriginalName();
        String receiverDestinationPackageName = receiverObject.getDestinationName();

        // Need to check both cases
        // If the source method's package is renamed after the inline method refactoring
        if(dispatcherOriginalClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherOriginalClassName.lastIndexOf("."));
            // Update the classes package
            ((InlineMethodObject) dispatcher).setOriginalClassName(receiverDestinationPackageName + refactoredClassName);
        }

        // If the extracted method's package is renamed after the inline method refactoring
        if(dispatcherRefactoredClassName.contains(receiverOriginalPackageName)) {
            String refactoredClassName = dispatcherRefactoredClassName.substring(dispatcherRefactoredClassName.lastIndexOf("."));
            // Update the classes package
            ((InlineMethodObject) dispatcher).setDestinationClassName(receiverDestinationPackageName + refactoredClassName);
        }
    }

}
