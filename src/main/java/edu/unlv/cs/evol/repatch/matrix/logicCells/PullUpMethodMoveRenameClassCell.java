package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.PullUpMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import com.intellij.openapi.project.Project;

public class PullUpMethodMoveRenameClassCell {
    Project project;

    public PullUpMethodMoveRenameClassCell(Project project) {
        this.project = project;
    }

    public void checkCombination(RefactoringObject dispatcherObject, RefactoringObject receiverObject) {
        MoveRenameClassObject dispatcher = (MoveRenameClassObject) dispatcherObject;
        PullUpMethodObject receiver = (PullUpMethodObject) receiverObject;

        String dispatcherOriginalClass = dispatcher.getOriginalClassObject().getClassName();
        String receiverOriginalClass = receiver.getOriginalClass();
        String dispatcherOriginalFile = dispatcher.getOriginalFilePath();
        String receiverOriginalFile = receiver.getOriginalFilePath();

        String dispatcherDestinationClass = dispatcher.getDestinationClassObject().getClassName();
        String receiverDestinationClass = receiver.getTargetClass();
        String dispatcherDestinationFile = dispatcher.getDestinationFilePath();
        String receiverDestinationFile = receiver.getDestinationFilePath();

        // If the pull up method refactoring happens after the class refactoring, update the corresponding method's location
        if(dispatcherDestinationClass.equals(receiverOriginalClass) && dispatcherDestinationFile.equals(receiverOriginalFile)) {
            receiverObject.setOriginalFilePath(dispatcherOriginalFile);
            ((PullUpMethodObject) receiverObject).setOriginalClass(dispatcherOriginalClass);
        }

        if(dispatcherDestinationClass.equals(receiverDestinationClass) && dispatcherDestinationFile.equals(receiverDestinationFile)) {
            receiverObject.setDestinationFilePath(dispatcherOriginalFile);
            ((PullUpMethodObject) receiverObject).setTargetClass(dispatcherOriginalClass);
        }



    }

}
