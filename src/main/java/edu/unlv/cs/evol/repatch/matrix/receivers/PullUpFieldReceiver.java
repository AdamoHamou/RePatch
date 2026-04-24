package edu.unlv.cs.evol.repatch.matrix.receivers;

import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameClassDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameFieldDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.PullUpFieldDispatcher;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PullUpFieldMoveRenameClassCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PullUpFieldMoveRenameFieldCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PullUpFieldPullUpFieldCell;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;

public class PullUpFieldReceiver extends Receiver {

    /*
     * Check for pull up field / pull up field naming and accidental shadowing conflicts. If on the same branch, check
     * for transitivity.
     */
    @Override
    public void receive(PullUpFieldDispatcher dispatcher) {
        RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
        PullUpFieldPullUpFieldCell cell = new PullUpFieldPullUpFieldCell(project);
        if(dispatcher.isSimplify()) {
            // Dispatcher refactoring is always the second refactoring when dealing with two refactorings of the same type
            this.isTransitive = cell.checkTransitivity(this.refactoringObject, dispatcherRefactoring);
            dispatcher.setRefactoringObject(dispatcherRefactoring);
        }

        if(!dispatcher.isSimplify()) {
            boolean isConflicting = cell.conflictCell(dispatcherRefactoring, this.refactoringObject);
            if(isConflicting) {
                dispatcherRefactoring.setReplayFlag(false);
                this.refactoringObject.setReplayFlag(false);
            }
        }
    }

    /*
     * Checks for pull up field/move + rename field conflicts and combination
     */
    @Override
    public void receive(MoveRenameFieldDispatcher dispatcher) {
        PullUpFieldMoveRenameFieldCell cell = new PullUpFieldMoveRenameFieldCell(project);
        RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
        if(dispatcher.isSimplify()) {
            this.isTransitive = false;
            // There is no opportunity for transitivity in this case. There is only a combination case that can occur
            cell.checkCombination(dispatcherRefactoring, this.refactoringObject);
            dispatcher.setRefactoringObject(dispatcherRefactoring);
        }
        else {
            boolean isConflicting = cell.conflictCell(dispatcherRefactoring, this.refactoringObject);
            if(isConflicting) {
                dispatcherRefactoring.setReplayFlag(false);
                this.refactoringObject.setReplayFlag(false);
            }
        }

    }

    /*
     * Checks for pull up field/move + rename class conflicts and combination
     */
    public void receive(MoveRenameClassDispatcher dispatcher) {
        PullUpFieldMoveRenameClassCell cell = new PullUpFieldMoveRenameClassCell(project);
        RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
        if(dispatcher.isSimplify()) {
            this.isTransitive = false;
            // There is no opportunity for transitivity in this case. There is only a combination case that can occur
            cell.checkCombination(dispatcherRefactoring, this.refactoringObject);
            dispatcher.setRefactoringObject(dispatcherRefactoring);
        }
        // No conflicts possible between class + field levels

    }

}
