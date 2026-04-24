package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;

public class InlineMethodMoveRenameClassCell {

    /*
     * Check if inline method and move+rename class refactorings have dependence between branches
     */
    public static boolean checkDependence(RefactoringObject moveRenameClass, RefactoringObject inlineMethod) {
        MoveRenameClassObject moveRenameClassObject = (MoveRenameClassObject) moveRenameClass;
        InlineMethodObject inlineMethodObject = (InlineMethodObject) inlineMethod;

        String moveRenameOriginalClass = moveRenameClassObject.getOriginalClassObject().getClassName();
        String inlineDestinationClass = inlineMethodObject.getDestinationClassName();

        // If the method is inlined to a method in the class that is refactored, there is dependence.
        return moveRenameOriginalClass.equals(inlineDestinationClass);
    }

    /*
     * Check if inline method and move+rename class refactorings can be combined on the same branch
     */
    public static void checkCombination(RefactoringObject moveRenameClass, RefactoringObject inlineMethod) {
        MoveRenameClassObject moveRenameClassObject = (MoveRenameClassObject) moveRenameClass;
        InlineMethodObject inlineMethodObject = (InlineMethodObject) inlineMethod;

        String moveRenameOriginalClass = moveRenameClassObject.getOriginalClassObject().getClassName();
        String inlineDestinationClass = inlineMethodObject.getDestinationClassName();
        String moveRenameDestinationClass = moveRenameClassObject.getDestinationClassObject().getClassName();
        String inlineOriginalClass = inlineMethodObject.getOriginalClassName();

        // If the method is inlined before the class that it is inlined to is refactored
        if(moveRenameOriginalClass.equals(inlineDestinationClass)) {
            // Update the target class
            inlineMethod.setDestinationFilePath(moveRenameClass.getDestinationFilePath());
            ((InlineMethodObject) inlineMethod).setDestinationClassName(moveRenameDestinationClass);
        }
        // If the method is inlined after the class that it is inlined from is refactored, update the original method
        else if(moveRenameDestinationClass.equals(inlineOriginalClass)) {
            inlineMethod.setOriginalFilePath(moveRenameClass.getOriginalFilePath());
            ((InlineMethodObject) inlineMethod).setOriginalClassName(moveRenameOriginalClass);
        }
    }
}
