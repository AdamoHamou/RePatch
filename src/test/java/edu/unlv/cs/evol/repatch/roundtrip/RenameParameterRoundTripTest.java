package edu.unlv.cs.evol.repatch.roundtrip;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import edu.unlv.cs.evol.repatch.invertOperations.InvertRenameParameter;
import edu.unlv.cs.evol.repatch.refactoringObjects.RenameParameterObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import edu.unlv.cs.evol.repatch.replayOperations.ReplayRenameParameter;

import java.util.Arrays;

/*
 * SPEC-12b / SPEC-2: invert -> replay round-trip for RENAME_PARAMETER — the
 * refactoring type the paper identifies as driving 41.1% of conflicts, whose
 * replay path was dead until this branch. The invert must restore the
 * original parameter name in signature and body, and the replay must restore
 * the refactored form.
 */
public class RenameParameterRoundTripTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/roundTrip";
    }

    private static MethodSignatureObject signature(String parameterName) {
        return new MethodSignatureObject(Arrays.asList(
                new ParameterObject("int", "return"),
                new ParameterObject("int", parameterName)), "handle");
    }

    public void testInvertThenReplayRenameParameter() {
        myFixture.configureByFile("renameParameter/refactored/Handler.java");

        RenameParameterObject refactoring = new RenameParameterObject(
                "Handler", "Handler",
                signature("count"), signature("amount"),
                new ParameterObject("int", "count"),
                new ParameterObject("int", "amount"));
        refactoring.setOriginalFilePath("Handler.java");
        refactoring.setDestinationFilePath("Handler.java");

        new InvertRenameParameter(getProject()).invertRenameParameter(refactoring);
        myFixture.checkResultByFile("renameParameter/original/Handler.java");

        new ReplayRenameParameter(getProject()).replayRenameParameter(refactoring);
        myFixture.checkResultByFile("renameParameter/refactored/Handler.java");
    }
}
