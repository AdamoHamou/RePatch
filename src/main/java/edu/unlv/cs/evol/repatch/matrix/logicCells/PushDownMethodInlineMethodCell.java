package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.InlineMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.PushDownMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import com.intellij.openapi.project.Project;

public class PushDownMethodInlineMethodCell {

    Project project;

    public PushDownMethodInlineMethodCell(Project project) {
        this.project = project;
    }

    public boolean conflictCell(RefactoringObject dispatcher, RefactoringObject receiver) {
        InlineMethodObject inlineMethodObject = (InlineMethodObject) dispatcher;
        PushDownMethodObject pushDownMethodObject = (PushDownMethodObject) receiver;
        // Override conflict

        // Overload conflict

        // Naming conflict
        return namingConflict(inlineMethodObject, pushDownMethodObject);

    }

    public boolean namingConflict(InlineMethodObject dispatcher, PushDownMethodObject receiver) {
        String dispatcherOriginalClass = dispatcher.getOriginalClassName();
        String receiverOriginalClass = receiver.getOriginalClass();

        // Only need to get the original signatures because the inline method refactoring destroys the program element when
        // the refactoring is performed.
        MethodSignatureObject dispatcherOriginalMethod = dispatcher.getOriginalMethodSignature();
        MethodSignatureObject receiverOriginalMethod = receiver.getOriginalMethodSignature();


        // If two methods are pushed down from and inlined from the same method, it is conflicting
        return dispatcherOriginalClass.equals(receiverOriginalClass)
                && dispatcherOriginalMethod.equalsSignature(receiverOriginalMethod);
    }


}
