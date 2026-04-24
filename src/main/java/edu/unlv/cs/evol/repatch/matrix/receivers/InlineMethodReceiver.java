package edu.unlv.cs.evol.repatch.matrix.receivers;

import edu.unlv.cs.evol.repatch.matrix.dispatcher.ExtractMethodDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.InlineMethodDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameClassDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameMethodDispatcher;
import edu.unlv.cs.evol.repatch.matrix.logicCells.InlineMethodMoveRenameClassCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.InlineMethodMoveRenameMethodCell;
import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;

public class InlineMethodReceiver extends Receiver {
    /*
     *  Check if we can simplify inline method/move+rename method and update or if there is conflict/dependence.
     */
    @Override
    public void receive(MoveRenameMethodDispatcher dispatcher) {
        // Check for inline method/move+rename method combination
        if(dispatcher.isSimplify()) {
            RefactoringObject moveRenameMethod = dispatcher.getRefactoringObject();
            InlineMethodMoveRenameMethodCell.checkCombination(moveRenameMethod, this.refactoringObject);
            dispatcher.setRefactoringObject(moveRenameMethod);
        }
        // Check for inline method/move+rename method conflict
        else {
            RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
            boolean isConflicting = InlineMethodMoveRenameMethodCell.conflictCell(dispatcherRefactoring, this.refactoringObject);
            this.isConflicting = isConflicting;
            if(!isConflicting) {
                boolean isDependent = InlineMethodMoveRenameMethodCell.dependenceCell(dispatcherRefactoring, this.refactoringObject);
                if (isDependent) {
                    // If they are dependent, update the inline method refactoring
                    this.refactoringObject.setDestinationFilePath(dispatcherRefactoring.getDestinationFilePath());
                    ((InlineMethodObject) this.refactoringObject)
                            .setDestinationClassName(((MoveRenameMethodObject) dispatcherRefactoring).getDestinationClassName());
                    ((InlineMethodObject) this.refactoringObject)
                            .setDestinationMethodSignature(((MoveRenameMethodObject) dispatcherRefactoring).getDestinationMethodSignature());
                    dispatcher.setRefactoringObject(dispatcherRefactoring);
                }
            }
        }
    }

    /*
     * Check for transitivity and dependence in inline method/move+rename class and update.
     */
    @Override
    public void receive(MoveRenameClassDispatcher dispatcher) {
        RefactoringObject moveRenameClass = dispatcher.getRefactoringObject();
        // Check if the inline method and move+rename class refactorings can be combined
        if(dispatcher.isSimplify()) {
            InlineMethodMoveRenameClassCell.checkCombination(moveRenameClass, this.refactoringObject);
            dispatcher.setRefactoringObject(moveRenameClass);
        }
        // Check for dependence between branches
        else {
            boolean isDependent = InlineMethodMoveRenameClassCell.checkDependence(moveRenameClass, this.refactoringObject);
            if(isDependent) {
                this.refactoringObject.setDestinationFilePath(moveRenameClass.getDestinationFilePath());
                ((InlineMethodObject) this.refactoringObject)
                        .setDestinationClassName(((MoveRenameClassObject) moveRenameClass).getDestinationClassObject().getClassName());
            }
        }
    }

    /*
     * Check for inline method/extract method conflicts.
     */
    @Override
    public void receive(ExtractMethodDispatcher dispatcher) {
        // Placeholder for if inline method/extract method can result in conflicts/dependence
    }

    /*
     * Check for inline method/inline method dependence
     */
    @Override
    public void receive(InlineMethodDispatcher dispatcher) {
        // Placeholder for if inline method/inline method can result in conflicts/dependence
    }
}
