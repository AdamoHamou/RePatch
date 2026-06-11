package edu.unlv.cs.evol.repatch.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;

/**
 * The single logging seam for the pipeline, carved out of the two {@code Utils}
 * classes (repatch + integration) which used to each carry a private copy of
 * the timestamp/stdout/file-append {@code log} method.
 *
 * <p>Three things this adds over the old {@code Utils.log}:
 * <ul>
 *   <li><b>Levels.</b> DEBUG/INFO/WARN/ERROR with a process-wide threshold
 *       (default INFO, overridable with {@code -Drepatch.log.level=DEBUG}).
 *       The legacy {@code Utils.log} path logs at INFO so its output is
 *       unchanged.</li>
 *   <li><b>A per-integration operation id.</b> The pipeline evaluates one PR
 *       scenario at a time; {@link #setOperationContext} stamps every line
 *       emitted on that thread for the duration of the scenario with an id
 *       like {@code PR-13050}, so a multi-PR run log is greppable per PR
 *       without threading a logger object through {@code RePatch.doMerge} and
 *       the invert/replay call tree. Lines dispatched onto the EDT do not
 *       inherit the context — bind a logger instance explicitly there if
 *       needed.</li>
 *   <li><b>Greppable prefixes preserved.</b> The level and op id are prepended;
 *       the caller's message (and its {@code [GitPipeline]} /
 *       {@code [RefactoringExecution]} / {@code [PipelineSummary]} prefix) is
 *       appended verbatim, so existing greps still match.</li>
 * </ul>
 */
public class LoggingService {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static final boolean LOG_TO_FILE = true;
    private static final String DEFAULT_LOG_FILE = "log.txt";
    private static final String TIMESTAMP_PATTERN = "MM/dd/yyyy HH:mm:ss z";

    private static volatile Level threshold = initialThreshold();

    /**
     * Operation id for the scenario currently running on this thread. The
     * pipeline sets it around each PR's merge scenario; everything that logs
     * on that thread — {@code RePatch.doMerge}, the invert/replay operations,
     * {@code GitUtils} — is stamped with it without taking a logger argument.
     */
    private static final ThreadLocal<String> CONTEXT_OPERATION = new ThreadLocal<>();

    private final String projectName;
    private final String operationId;

    private LoggingService(String projectName, String operationId) {
        this.projectName = projectName;
        this.operationId = operationId;
    }

    /** A logger that routes its file output to {@code projectName} and inherits the thread's operation context. */
    public static LoggingService forProject(String projectName) {
        return new LoggingService(projectName, null);
    }

    /** A logger explicitly bound to an operation id, independent of thread context (use across EDT hops). */
    public static LoggingService forOperation(String projectName, String operationId) {
        return new LoggingService(projectName, operationId);
    }

    /** Derive a logger bound to {@code operationId} from this one, keeping the same file routing. */
    public LoggingService withOperation(String operationId) {
        return new LoggingService(this.projectName, operationId);
    }

    // ---- threshold control -------------------------------------------------

    public static void setThreshold(Level level) {
        threshold = level;
    }

    public static Level getThreshold() {
        return threshold;
    }

    /** Whether a line at {@code level} would be emitted under the current threshold. */
    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    private static Level initialThreshold() {
        String configured = System.getProperty("repatch.log.level");
        if (configured != null) {
            try {
                return Level.valueOf(configured.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to default on a bad value
            }
        }
        return Level.INFO;
    }

    // ---- pure formatting (unit-tested) -------------------------------------

    /**
     * Render the level/op/message portion of a log line, without the leading
     * timestamp. Deterministic, so it is the unit-test surface for the format.
     * Examples: {@code "INFO [PR-13050] [GitPipeline] COMMIT_NOOP"},
     * {@code "WARN preflight skipped"}.
     */
    static String format(Level level, String operationId, String message) {
        StringBuilder sb = new StringBuilder(level.name());
        if (operationId != null && !operationId.trim().isEmpty()) {
            sb.append(" [").append(operationId.trim()).append(']');
        }
        sb.append(' ').append(message == null ? "null" : message);
        return sb.toString();
    }

    /** Flatten any message argument (String / Throwable / other) the way the legacy {@code Utils.log} did. */
    static String renderMessage(Object message) {
        if (message instanceof String) {
            return (String) message;
        }
        if (message instanceof Throwable) {
            Throwable t = (Throwable) message;
            StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage())).append('\n');
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length; i++) {
                sb.append(frames[i].toString());
                if (i < frames.length - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
        return String.valueOf(message);
    }

    // ---- emission ----------------------------------------------------------

    public void debug(Object message) {
        log(Level.DEBUG, message);
    }

    public void info(Object message) {
        log(Level.INFO, message);
    }

    public void warn(Object message) {
        log(Level.WARN, message);
    }

    public void error(Object message) {
        log(Level.ERROR, message);
    }

    public void log(Level level, Object message) {
        if (!isEnabled(level)) {
            return;
        }
        String op = this.operationId != null ? this.operationId : CONTEXT_OPERATION.get();
        String timeStamp = new SimpleDateFormat(TIMESTAMP_PATTERN).format(new Date());
        String line = timeStamp + " " + format(level, op, renderMessage(message));

        System.out.println(line);

        if (LOG_TO_FILE) {
            appendToFile(line);
        }
    }

    private void appendToFile(String line) {
        String logFile = (projectName != null && !projectName.trim().isEmpty())
                ? projectName : DEFAULT_LOG_FILE;
        try {
            String dir = System.getProperty("user.home") + "/temp/logs/";
            new File(dir).mkdirs();
            Files.write(Paths.get(dir + logFile), Collections.singletonList(line),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- per-scenario operation context ------------------------------------

    /** Stamp every subsequent line on this thread with {@code operationId} until {@link #clearOperationContext}. */
    public static void setOperationContext(String operationId) {
        CONTEXT_OPERATION.set(operationId);
    }

    public static void clearOperationContext() {
        CONTEXT_OPERATION.remove();
    }

    public static String currentOperationId() {
        return CONTEXT_OPERATION.get();
    }

    // ---- legacy static entry point (delegated to by both Utils.log) --------

    /**
     * Back-compatible replacement for the old {@code Utils.log(projectName, message)}:
     * logs at INFO, picking up any thread operation context. Kept so the
     * many existing call sites need no change.
     */
    public static void log(String projectName, Object message) {
        forProject(projectName).log(Level.INFO, message);
    }
}
