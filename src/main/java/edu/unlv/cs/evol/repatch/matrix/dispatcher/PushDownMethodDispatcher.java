package edu.unlv.cs.evol.repatch.matrix.dispatcher;

import edu.unlv.cs.evol.repatch.matrix.receivers.Receiver;

public class PushDownMethodDispatcher extends RefactoringDispatcher {
    @Override
    public void dispatch(Receiver r) {
        r.receive(this);
    }
}
