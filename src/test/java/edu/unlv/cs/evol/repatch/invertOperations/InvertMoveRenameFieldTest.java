package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.InMemoryPlatformFacade;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionResult;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionService;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameFieldObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.testUtils.GetDataForTests;
import edu.unlv.cs.evol.repatch.testUtils.TestUtils;
import edu.unlv.cs.evol.repatch.utils.RefactoringObjectUtils;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.refactoringminer.api.Refactoring;

import java.util.List;

/**
 * Regression tests for the move/rename field family on the Week 4A
 * contract (Workstream 5B): a RENAME_ATTRIBUTE refactoring detected from
 * the fixture pair is inverted through {@link RefactoringExecutionService},
 * and the renamed source ends up matching {@code expectedUndoResults}; a
 * missing field target classifies as a precondition failure (the legacy
 * class passed the null member straight into the move processor).
 */
public class InvertMoveRenameFieldTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources";
    }

    public void testInvertRenameFieldMatchesExpectedUndoResults() {
        String testDir = "renameTestData/fieldRenameTestData/";
        String testDataRenamed = testDir + "renamed/";
        String testDataOriginal = testDir + "original/";
        String testFile = "FieldRenameTestData.java";
        // Configure only the renamed copy: a second copy of the same
        // package-qualified class (e.g. expectedUndoResults) makes the
        // prepare-step class lookup ambiguous in the light fixture.
        PsiFile psiFile = myFixture.configureByFile(testDataRenamed + testFile);
        String basePath = System.getProperty("user.dir");
        String refactoredPath = basePath + "/" + getTestDataPath() + "/" + testDataRenamed;
        String originalPath = basePath + "/" + getTestDataPath() + "/" + testDataOriginal;

        PsiField[] renamedFields = TestUtils.getPsiFieldsFromFile(psiFile);
        // Field names of the original/ fixture — what undoing the rename must restore.
        List<String> expectedFieldNames = List.of("count", "label");
        assertFalse("fixture must start un-inverted",
                TestUtils.getFieldNames(renamedFields).equals(expectedFieldNames));

        List<Refactoring> refactorings = GetDataForTests.getRefactorings("RENAME_ATTRIBUTE", originalPath, refactoredPath);
        assertNotNull("RefactoringMiner found no RENAME_ATTRIBUTE in the fixture pair", refactorings);
        assertFalse("RefactoringMiner found no RENAME_ATTRIBUTE in the fixture pair", refactorings.isEmpty());
        RefactoringObject refactoringObject = RefactoringObjectUtils.createRefactoringObject(refactorings.get(0));

        InMemoryPlatformFacade facade = new InMemoryPlatformFacade();
        RefactoringExecutionService service = new RefactoringExecutionService(
                new RefactoringExecutionContext(getProject(), facade));

        RefactoringExecutionResult result = service.execute(new InvertMoveRenameField(refactoringObject));

        assertTrue("inversion should succeed through the contract: " + result.describe(), result.isSuccess());
        assertEquals("post-operation sync must have run", 1, facade.commitAndReparseCount);
        assertSameElements(TestUtils.getFieldNames(renamedFields), expectedFieldNames);
    }

    public void testMissingFieldTargetClassifiesAsPreconditionFailure() {
        String testDir = "renameTestData/fieldRenameTestData/";
        String testFile = "FieldRenameTestData.java";
        myFixture.configureByFiles(testDir + "renamed/" + testFile);

        // A refactoring whose destination field does not exist in the source —
        // the legacy code would have handed the null member to the processor.
        MoveRenameFieldObject fieldObject = new MoveRenameFieldObject(
                testDir + "renamed/" + testFile, "renameTestData.fieldRenameTestData.FieldRenameTestData", "ghost",
                testDir + "renamed/" + testFile, "renameTestData.fieldRenameTestData.FieldRenameTestData", "phantom");
        fieldObject.setType(org.refactoringminer.api.RefactoringType.RENAME_ATTRIBUTE);

        InMemoryPlatformFacade facade = new InMemoryPlatformFacade();
        RefactoringExecutionService service = new RefactoringExecutionService(
                new RefactoringExecutionContext(getProject(), facade));

        RefactoringExecutionResult result = service.execute(new InvertMoveRenameField(fieldObject));

        assertFalse("missing field must not succeed", result.isSuccess());
        assertEquals(RefactoringExecutionResult.Status.PRECONDITION_FAILED, result.getStatus());
        assertEquals("nothing mutated, so no post-operation sync", 0, facade.commitAndReparseCount);
    }
}
