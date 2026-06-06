package edu.unlv.cs.evol.integration.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the vendored flattenString to the exact semantics of IntelliMerge
 * 1.0.7's edu.pku.intellimerge.util.Utils#flattenString, verified by running
 * the original class before the fat jar was removed. The crucial property:
 * ALL whitespace is removed, with NO separator left behind — conflict-block
 * content comparison (and thus the verdicts) depends on it.
 */
public class EvaluationUtilsFlattenStringTest {

    @Test
    public void stripsAllWhitespaceWithoutSeparators() {
        // exact case run against the original jar: [abcde], not [a b c d e]
        assertEquals("abcde", EvaluationUtils.flattenString("  a\r\nb\rc\nd\t\te  "));
    }

    @Test
    public void flattensTypicalConflictContent() {
        assertEquals("inti=0;intj=1;",
                EvaluationUtils.flattenString("int i = 0;\n    int j = 1;\n"));
        assertEquals("", EvaluationUtils.flattenString("   \r\n\t  "));
        assertEquals("x", EvaluationUtils.flattenString("x"));
    }
}
