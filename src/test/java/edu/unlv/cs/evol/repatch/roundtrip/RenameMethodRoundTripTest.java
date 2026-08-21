package edu.unlv.cs.evol.repatch.roundtrip;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import edu.unlv.cs.evol.repatch.invertOperations.InvertMoveRenameMethod;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameMethodObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import edu.unlv.cs.evol.repatch.replayOperations.ReplayMoveRenameMethod;
import org.refactoringminer.api.RefactoringType;

import java.util.Arrays;

/*
 * SPEC-12b: invert -> replay round-trip for RENAME_METHOD over a real PSI
 * fixture (headless IntelliJ test harness). The invert must reproduce the
 * original source exactly, and the replay must restore the refactored form —
 * declaration AND call sites.
 */
public class RenameMethodRoundTripTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/roundTrip";
    }

    private static MethodSignatureObject signature(String name) {
        // Convention: the parameter list includes the return type as a
        // ParameterObject named "return" (see Utils.ifSameMethods).
        return new MethodSignatureObject(Arrays.asList(
                new ParameterObject("int", "return"),
                new ParameterObject("int", "a"),
                new ParameterObject("int", "b")), name);
    }

    public void testInvertThenReplayRenameMethod() {
        myFixture.configureByFile("renameMethod/refactored/Calc.java");

        MoveRenameMethodObject refactoring = new MoveRenameMethodObject(
                RefactoringType.RENAME_METHOD, "",
                "Calc.java", "Calc", signature("plus"),
                "Calc.java", "Calc", signature("add"));

        new InvertMoveRenameMethod(getProject()).invertMoveRenameMethod(refactoring);
        myFixture.checkResultByFile("renameMethod/original/Calc.java");

        new ReplayMoveRenameMethod(getProject()).replayMoveRenameMethod(refactoring);
        myFixture.checkResultByFile("renameMethod/refactored/Calc.java");
    }
}
