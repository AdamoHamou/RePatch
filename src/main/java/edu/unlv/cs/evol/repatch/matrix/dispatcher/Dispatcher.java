package edu.unlv.cs.evol.repatch.matrix.dispatcher;

import edu.unlv.cs.evol.repatch.matrix.receivers.Receiver;

public interface Dispatcher {
    void dispatch(Receiver r);
}

