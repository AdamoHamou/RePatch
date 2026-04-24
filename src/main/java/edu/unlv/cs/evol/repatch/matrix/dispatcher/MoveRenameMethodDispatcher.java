package edu.unlv.cs.evol.repatch.matrix.dispatcher;

import edu.unlv.cs.evol.repatch.matrix.receivers.Receiver;


/*
 * Dispatches the rename+move method refactoring to the corresponding receiver.
 */
public class MoveRenameMethodDispatcher extends RefactoringDispatcher {
    @Override
    public void dispatch(Receiver r) {
        r.receive(this);
    }

}
