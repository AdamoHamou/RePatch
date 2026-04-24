package edu.unlv.cs.evol.repatch.matrix.logicCells;

import com.intellij.openapi.project.Project;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownFieldObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;

public class PushDownFieldMoveRenameClassCell {
    Project project;

    public PushDownFieldMoveRenameClassCell(Project project) {
        this.project = project;
    }

    public void checkCombination(RefactoringObject dispatcherObject, RefactoringObject receiverObject) {
        MoveRenameClassObject dispatcher = (MoveRenameClassObject) dispatcherObject;
        PushDownFieldObject receiver = (PushDownFieldObject) receiverObject;

        String dispatcherOriginalClass = dispatcher.getOriginalClassObject().getClassName();
        String receiverOriginalClass = receiver.getOriginalClass();
        String dispatcherOriginalFile = dispatcher.getOriginalFilePath();
        String receiverOriginalFile = receiver.getOriginalFilePath();

        String dispatcherDestinationClass = dispatcher.getDestinationClassObject().getClassName();
        String receiverDestinationClass = receiver.getTargetSubClass();
        String dispatcherDestinationFile = dispatcher.getDestinationFilePath();
        String receiverDestinationFile = receiver.getDestinationFilePath();

        // If the push down field refactoring happens after the class refactoring, update the corresponding field's location
        if(dispatcherDestinationClass.equals(receiverOriginalClass) && dispatcherDestinationFile.equals(receiverOriginalFile)) {
            receiverObject.setOriginalFilePath(dispatcherOriginalFile);
            ((PushDownFieldObject) receiverObject).setOriginalClass(dispatcherOriginalClass);
        }

        if(dispatcherDestinationClass.equals(receiverDestinationClass) && dispatcherDestinationFile.equals(receiverDestinationFile)) {
            receiverObject.setDestinationFilePath(dispatcherOriginalFile);
            ((PushDownFieldObject) receiverObject).setTargetSubClass(dispatcherOriginalClass);
        }
    }
}
