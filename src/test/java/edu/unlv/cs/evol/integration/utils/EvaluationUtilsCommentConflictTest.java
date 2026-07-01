package edu.unlv.cs.evol.integration.utils;

import edu.unlv.cs.evol.integration.data.ConflictBlockData;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Pins the comment-conflict handling that restores 12660 parity: RePatch
 * resolves comment-only (Javadoc / line-comment) conflicts as part of its
 * merge, so they are NOT counted for RePatch, while the plain git baseline
 * still counts them. A conflict that falls inside a Javadoc block is captured
 * as continuation lines starting with '*', which must be recognized as a
 * comment even though it is not a self-contained {@code /* ... *}{@code /}.
 */
public class EvaluationUtilsCommentConflictTest {

    // A Javadoc @param conflict (the BatchAccumulator 12660 shape) followed by a
    // real code conflict. Markers sit outside the /** */ delimiters, so the
    // captured Javadoc content is pure '*' continuation lines.
    private static final String CONTENT = String.join("\n",
            "    /**",
            "     * Append a record.",
            "     *",
            "<<<<<<< HEAD",
            "     * @param @currentTimeMs The timestamp of message generation",
            "=======",
            "     * @param currentTimestamp The current time in milliseconds",
            ">>>>>>> bf7ddf73af (#12660)",
            "     * @throws IllegalStateException on failure",
            "     */",
            "    void append() {",
            "<<<<<<< HEAD",
            "        int x = 1;",
            "=======",
            "        int x = 2;",
            ">>>>>>> bf7ddf73af (#12660)",
            "    }",
            "");

    private Path writeTempFile() throws IOException {
        Path f = Files.createTempFile("conflict", ".java");
        Files.write(f, CONTENT.getBytes());
        return f;
    }

    @Test
    public void repatchDropsCommentConflictButKeepsCode() throws IOException {
        Path f = writeTempFile();
        List<ConflictBlockData> blocks =
                EvaluationUtils.extractConflictBlocks(f.toString(), "RePatch", true);
        // Javadoc @param conflict excluded; the code conflict remains.
        assertEquals(1, blocks.size());
    }

    @Test
    public void gitCountsBothCommentAndCode() throws IOException {
        Path f = writeTempFile();
        List<ConflictBlockData> blocks =
                EvaluationUtils.extractConflictBlocks(f.toString(), "Git-CherryPick", true);
        assertEquals(2, blocks.size());
    }
}
