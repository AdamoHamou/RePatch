package edu.unlv.cs.evol.repatch;

import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import org.junit.Test;
import org.refactoringminer.api.RefactoringType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/*
 * SPEC-1: detection scoping keeps only refactorings that touch a file git
 * failed to auto-merge — with fail-open on missing scope and a carve-out for
 * path-less (inherently cross-file) refactoring types.
 */
public class DetectionScopeTest {

    private static RefactoringObject refactoringIn(String originalPath, String destinationPath) {
        return new MoveRenameMethodObject(RefactoringType.RENAME_METHOD, "",
                originalPath, "A", null, destinationPath, "A", null);
    }

    private static ArrayList<RefactoringObject> list(RefactoringObject... objects) {
        return new ArrayList<>(Arrays.asList(objects));
    }

    @Test
    public void refactoringOutsideConflictingFilesIsDropped() {
        Set<String> conflicting = new HashSet<>(Collections.singletonList("a/A.java"));
        ArrayList<RefactoringObject> scoped = RePatch.filterToConflictingFiles("c",
                list(refactoringIn("b/B.java", "b/B.java")), conflicting);
        assertTrue(scoped.isEmpty());
    }

    @Test
    public void originalPathMatchIsKept() {
        Set<String> conflicting = new HashSet<>(Collections.singletonList("a/A.java"));
        ArrayList<RefactoringObject> scoped = RePatch.filterToConflictingFiles("c",
                list(refactoringIn("a/A.java", "b/B.java")), conflicting);
        assertEquals(1, scoped.size());
    }

    @Test
    public void destinationPathMatchIsKept() {
        Set<String> conflicting = new HashSet<>(Collections.singletonList("a/A.java"));
        ArrayList<RefactoringObject> scoped = RePatch.filterToConflictingFiles("c",
                list(refactoringIn("b/B.java", "a/A.java")), conflicting);
        assertEquals(1, scoped.size());
    }

    @Test
    public void pathlessRefactoringIsAlwaysKept() {
        // e.g. Rename Package: no file paths, inherently cross-file.
        Set<String> conflicting = new HashSet<>(Collections.singletonList("a/A.java"));
        ArrayList<RefactoringObject> scoped = RePatch.filterToConflictingFiles("c",
                list(refactoringIn(null, null)), conflicting);
        assertEquals(1, scoped.size());
    }

    @Test
    public void nullOrEmptyScopeFailsOpen() {
        ArrayList<RefactoringObject> all = list(refactoringIn("b/B.java", "b/B.java"));
        assertSame(all, RePatch.filterToConflictingFiles("c", all, null));
        assertSame(all, RePatch.filterToConflictingFiles("c", all, new HashSet<>()));
    }
}
