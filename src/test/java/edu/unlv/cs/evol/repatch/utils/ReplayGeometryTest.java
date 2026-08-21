package edu.unlv.cs.evol.repatch.utils;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenameParameterObject;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.refactoringminer.api.RefactoringType;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/*
 * SPEC-3/SPEC-12: the conflict-region pruning geometry in
 * Utils.checkReplayRefactoring, tested as pure region math (no IDE, no PSI).
 */
public class ReplayGeometryTest {

    private final Utils utils = new Utils(null);

    private static RefactoringObject methodRename(int startLine, int endLine) {
        MoveRenameMethodObject object = new MoveRenameMethodObject(
                RefactoringType.RENAME_METHOD, "", "A.java", "A", null, "A.java", "A", null);
        object.setStartLine(startLine);
        object.setEndLine(endLine);
        return object;
    }

    private static RefactoringObject parameterRename(int startLine, int endLine) {
        RenameParameterObject object = new RenameParameterObject(
                "A", "A", null, null, null, null);
        object.setStartLine(startLine);
        object.setEndLine(endLine);
        return object;
    }

    private static List<Pair<Integer, Integer>> region(int start, int end) {
        return Collections.singletonList(Pair.of(start, end));
    }

    @Test
    public void noBoundariesIsNeverReplayed() {
        // Types without boundary support report 0/0; edit surface unknown.
        assertFalse(utils.checkReplayRefactoring(methodRename(0, 0), region(10, 20)));
    }

    @Test
    public void disjointRegionIsReplayed() {
        assertTrue(utils.checkReplayRefactoring(methodRename(30, 40), region(10, 20)));
        assertTrue(utils.checkReplayRefactoring(methodRename(1, 9), region(10, 20)));
    }

    @Test
    public void conflictStrictlyInsideMethodRenameIsReplayed() {
        // A method rename edits the signature, not the body: a conflict
        // strictly inside the method's range does not interact with it.
        assertTrue(utils.checkReplayRefactoring(methodRename(5, 50), region(10, 20)));
    }

    @Test
    public void conflictStrictlyInsideParameterRenameIsPruned() {
        // A parameter rename edits usages across the whole method body,
        // including the conflicted lines.
        assertFalse(utils.checkReplayRefactoring(parameterRename(5, 50), region(10, 20)));
    }

    @Test
    public void partialOverlapIsPruned() {
        assertFalse(utils.checkReplayRefactoring(methodRename(15, 30), region(10, 20)));
        assertFalse(utils.checkReplayRefactoring(methodRename(5, 15), region(10, 20)));
    }

    @Test
    public void boundaryTouchIsPruned() {
        // Sharing exactly the start or end line is an overlap, not disjoint.
        assertFalse(utils.checkReplayRefactoring(methodRename(20, 30), region(10, 20)));
        assertFalse(utils.checkReplayRefactoring(methodRename(5, 10), region(10, 20)));
    }

    @Test
    public void emptyRegionListIsReplayed() {
        assertTrue(utils.checkReplayRefactoring(methodRename(5, 15), Collections.emptyList()));
    }
}
