package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ClassObject;

public class RenamePackageMoveRenameClassCell {

    public static void checkCombination(RefactoringObject dispatcher, RefactoringObject receiver) {
        MoveRenameClassObject dispatcherObject = (MoveRenameClassObject) dispatcher;
        RenamePackageObject receiverObject = (RenamePackageObject) receiver;

        ClassObject classObject = dispatcherObject.getDestinationClassObject();
        String dispatcherDestinationPackageName = classObject.getPackageName();


        String receiverOriginalPackageName = receiverObject.getOriginalName();
        String receiverDestinationPackageName = receiverObject.getDestinationName();

        // If p1.c1 -> p1.c2 before p1.c2 -> p2.c2
        if(dispatcherDestinationPackageName.contains(receiverOriginalPackageName)) {
            classObject.setPackageName(receiverDestinationPackageName);
            // Update the destination package
            ((MoveRenameClassObject) dispatcher).setDestinationClassObject(classObject);
        }
    }

}
