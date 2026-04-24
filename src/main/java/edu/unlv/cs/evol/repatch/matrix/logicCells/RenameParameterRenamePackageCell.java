package edu.unlv.cs.evol.repatch.matrix.logicCells;

import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenamePackageObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenameParameterObject;

public class RenameParameterRenamePackageCell {

    public static void checkCombination(RefactoringObject renamePackageObject, RefactoringObject renameParameterObject) {
        RenamePackageObject renamePackage = (RenamePackageObject) renamePackageObject;
        RenameParameterObject renameParameter = (RenameParameterObject) renameParameterObject;

        String originalPackageName = renamePackage.getOriginalName();
        String destinationPackageName = renamePackage.getDestinationName();

        String originalQClassName = renameParameter.getOriginalClassName();
        String destinationQClassName = renameParameter.getRefactoredClassName();

        // If the parameter refactoring happens before the package refactoring
        // p1.C1.m1.pm1 -> p1.C1.m1.pm2 & p1.C1.m1.pm2 -> p2.C1.m1.pm2
        // Update the parameter's destination class path / package
        if(destinationQClassName.contains(originalPackageName)) {
            String destinationClassName = destinationQClassName.substring(destinationQClassName.lastIndexOf("."));
            // Update the class's package
            ((RenameParameterObject) renameParameterObject).setRefactoredClassName(destinationPackageName + destinationClassName);
        }


        // If the package refactoring happens before the parameter refactoring
        // p1.C1.m1.pm1 -> p2.C1.m1.pm1 & p2.C1.m1.pm1 -> p2.C1.m1.pm2
        // Update the parameter's original class path / package
        else if(originalQClassName.contains(destinationPackageName)) {
            String originalClassName = originalQClassName.substring(originalQClassName.lastIndexOf("."));
            // Update the class's package
            ((RenameParameterObject) renameParameterObject).setOriginalClassName(originalPackageName + originalClassName);
        }
    }

}
