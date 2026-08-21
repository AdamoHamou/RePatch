package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import org.junit.Test;
import org.refactoringminer.api.RefactoringType;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/*
 * SPEC-4: a refactoring whose inversion fails must be excluded from replay.
 * Matrix.detectConflicts builds the replay list from isReplay(), so the
 * contract under test is: markInversionFailed clears the flag.
 */
public class InversionFailureTest {

    @Test
    public void failedInversionIsExcludedFromReplay() {
        RefactoringObject refactoring = new MoveRenameMethodObject(
                RefactoringType.RENAME_METHOD, "", "A.java", "A", null, "A.java", "A", null);
        assertTrue(refactoring.isReplay());

        InvertRefactorings.markInversionFailed(refactoring, new RuntimeException("injected"));

        assertFalse(refactoring.isReplay());
    }
}
