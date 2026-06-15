package edu.unlv.cs.evol.repatch.invertOperations;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.Collection;
import java.util.List;

/**
 * Regression test for the 12660 chain-head throw: inverting an inline method
 * resolved its fragment endpoints to elements at different tree depths (a
 * nested {@code PsiReferenceExpression} and a top-level {@code PsiReturnStatement}),
 * which {@code PsiTreeUtil.getElementsOfRange} rejected with
 * {@code IllegalArgumentException: Invalid range}. {@link InvertInlineMethod#normalizeToSiblingRange}
 * lifts such endpoints to a valid common-parent sibling range; for endpoints
 * that already form a valid range it must be a no-op.
 */
public class InvertInlineMethodRangeTest extends LightJavaCodeInsightFixtureTestCase {

    private PsiCodeBlock body;
    private PsiStatement[] statements;
    private PsiReturnStatement returnStatement;
    private PsiReferenceExpression nestedRef;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("Sample.java",
                "class Sample {\n"
                        + "    int m() {\n"
                        + "        int a = 5;\n"
                        + "        int b = a + 1;\n"
                        + "        return b;\n"
                        + "    }\n"
                        + "}\n");
        PsiClass cls = file.getClasses()[0];
        PsiMethod method = cls.getMethods()[0];
        body = method.getBody();
        statements = body.getStatements();
        // statements[0] = int a = 5;  [1] = int b = a + 1;  [2] = return b;
        returnStatement = (PsiReturnStatement) statements[2];
        // A reference expression nested inside statement[1] (the 'a' in 'a + 1').
        Collection<PsiReferenceExpression> refs =
                PsiTreeUtil.findChildrenOfType(statements[1], PsiReferenceExpression.class);
        nestedRef = refs.iterator().next();
    }

    public void testOldRangeCallWouldThrowOnCrossDepthEndpoints() {
        // Documents the pre-fix failure mode: the nested reference expression and
        // the top-level return statement are not siblings, so the raw call throws.
        try {
            PsiTreeUtil.getElementsOfRange(nestedRef, returnStatement);
            fail("expected getElementsOfRange to reject cross-depth endpoints");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid range"));
        }
    }

    public void testNormalizeLiftsCrossDepthEndpointsToAValidSiblingRange() {
        PsiElement[] range = InvertInlineMethod.normalizeToSiblingRange(nestedRef, returnStatement);
        assertNotNull("cross-depth endpoints must normalize to a usable range", range);
        // Both endpoints lift to direct children of the method body.
        assertSame(body, range[0].getParent());
        assertSame(body, range[1].getParent());
        // Start is the statement containing the nested ref; end is the return.
        assertSame(statements[1], range[0]);
        assertSame(returnStatement, range[1]);
        // And the normalized range is now accepted by the platform.
        List<PsiElement> elements = PsiTreeUtil.getElementsOfRange(range[0], range[1]);
        assertTrue(elements.contains(statements[1]));
        assertTrue(elements.contains(returnStatement));
    }

    public void testNormalizeIsNoOpForAlreadyValidSiblingRange() {
        PsiElement[] range = InvertInlineMethod.normalizeToSiblingRange(statements[0], statements[2]);
        assertNotNull(range);
        assertSame(statements[0], range[0]);
        assertSame(statements[2], range[1]);
    }

    public void testNormalizeOrdersReversedEndpoints() {
        // Endpoints handed in reverse document order must come back ordered.
        PsiElement[] range = InvertInlineMethod.normalizeToSiblingRange(returnStatement, nestedRef);
        assertNotNull(range);
        assertSame(statements[1], range[0]);
        assertSame(returnStatement, range[1]);
    }
}
