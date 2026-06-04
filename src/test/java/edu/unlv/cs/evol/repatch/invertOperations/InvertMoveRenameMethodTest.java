package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.InMemoryPlatformFacade;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionResult;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionService;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.testUtils.GetDataForTests;
import edu.unlv.cs.evol.repatch.testUtils.TestUtils;
import edu.unlv.cs.evol.repatch.utils.RefactoringObjectUtils;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.refactoringminer.api.Refactoring;

import java.util.List;

/**
 * Regression test for the move/rename method family on the Week 4A
 * contract (Workstream 4C): a RENAME_METHOD refactoring detected from the
 * fixture pair is inverted through {@link RefactoringExecutionService},
 * and the renamed source ends up matching {@code expectedUndoResults}.
 */
public class InvertMoveRenameMethodTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources";
    }

    public void testInvertRenameMethodMatchesExpectedUndoResults() {
        String testDir = "renameTestData/methodRenameTestData/";
        String testDataRenamed = testDir + "renamed/";
        String testDataOriginal = testDir + "original/";
        String testResult = testDir + "expectedUndoResults/";
        String testFile = "MethodRenameTestData.java";
        PsiFile[] psiFiles = myFixture.configureByFiles(testDataRenamed + testFile, testResult + testFile);
        String basePath = System.getProperty("user.dir");
        String refactoredPath = basePath + "/" + getTestDataPath() + "/" + testDataRenamed;
        String originalPath = basePath + "/" + getTestDataPath() + "/" + testDataOriginal;

        PsiMethod[] renamedMethods = TestUtils.getPsiMethodsFromFile(psiFiles[0]);
        PsiMethod[] expectedMethods = TestUtils.getPsiMethodsFromFile(psiFiles[1]);
        assertFalse("fixture must start un-inverted",
                TestUtils.getMethodNames(renamedMethods).equals(TestUtils.getMethodNames(expectedMethods)));

        List<Refactoring> refactorings = GetDataForTests.getRefactorings("RENAME_METHOD", originalPath, refactoredPath);
        assertNotNull("RefactoringMiner found no RENAME_METHOD in the fixture pair", refactorings);
        assertFalse("RefactoringMiner found no RENAME_METHOD in the fixture pair", refactorings.isEmpty());
        RefactoringObject refactoringObject = RefactoringObjectUtils.createRefactoringObject(refactorings.get(0));

        InMemoryPlatformFacade facade = new InMemoryPlatformFacade();
        RefactoringExecutionService service = new RefactoringExecutionService(
                new RefactoringExecutionContext(getProject(), facade));

        RefactoringExecutionResult result = service.execute(new InvertMoveRenameMethod(refactoringObject));

        assertTrue("inversion should succeed through the contract: " + result.describe(), result.isSuccess());
        assertEquals("post-operation sync must have run", 1, facade.commitAndReparseCount);
        assertSameElements(TestUtils.getMethodNames(renamedMethods),
                TestUtils.getMethodNames(expectedMethods));
    }
}
