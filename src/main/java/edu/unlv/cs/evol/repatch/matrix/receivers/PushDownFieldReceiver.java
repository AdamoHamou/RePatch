package edu.unlv.cs.evol.repatch.matrix.receivers;

import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameClassDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.MoveRenameFieldDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.PullUpFieldDispatcher;
import edu.unlv.cs.evol.repatch.matrix.dispatcher.PushDownFieldDispatcher;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PushDownFieldMoveRenameClassCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PushDownFieldMoveRenameFieldCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PushDownFieldPullUpFieldCell;
import edu.unlv.cs.evol.repatch.matrix.logicCells.PushDownFieldPushDownFieldCell;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;

public class PushDownFieldReceiver extends Receiver {

    /*
     * Check for push down field / push down field naming and accidental shadowing conflicts. If on the same branch, check
     * for transitivity.
     */
    @Override
    public void receive(PushDownFieldDispatcher dispatcher) {
        RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
        PushDownFieldPushDownFieldCell cell = new PushDownFieldPushDownFieldCell(project);
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
     * Check for push down field / pull up field naming and shadow conflicts
     */
    @Override
    public void receive(PullUpFieldDispatcher dispatcher) {
        RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
        PushDownFieldPullUpFieldCell cell = new PushDownFieldPullUpFieldCell(project);
        if(!dispatcher.isSimplify()) {
            boolean isConflicting = cell.conflictCell(dispatcherRefactoring, this.refactoringObject);
            if(isConflicting) {
                dispatcherRefactoring.setReplayFlag(false);
                this.refactoringObject.setReplayFlag(false);
            }
        }
    }

    /*
     * Checks for push down field / move + rename field naming and shadow conflicts as well as combination
     */
    @Override
    public void receive(MoveRenameFieldDispatcher dispatcher) {
        PushDownFieldMoveRenameFieldCell cell = new PushDownFieldMoveRenameFieldCell(project);
        if(dispatcher.isSimplify()) {
            RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
            this.isTransitive = false;
            // There is no opportunity for transitivity in this case. There is only a combination case that can occur
            cell.checkCombination(dispatcherRefactoring, this.refactoringObject);
            dispatcher.setRefactoringObject(dispatcherRefactoring);
        }
        else {
            RefactoringObject dispatcherRefactoring = dispatcher.getRefactoringObject();
            boolean isConflicting = cell.conflictCell(dispatcherRefactoring, this.refactoringObject);
            if(isConflicting) {
                dispatcherRefactoring.setReplayFlag(false);
                this.refactoringObject.setReplayFlag(false);
            }
        }

    }

    /*
     * Checks for push down field / move + rename class conflicts and combination
     */
    public void receive(MoveRenameClassDispatcher dispatcher) {
        PushDownFieldMoveRenameClassCell cell = new PushDownFieldMoveRenameClassCell(project);
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
