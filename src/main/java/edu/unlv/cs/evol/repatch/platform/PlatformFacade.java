package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Single seam between RePatch's production code and the IntelliJ runtime.
 *
 * Every direct IntelliJ API call that RePatch's headless pipeline needs to
 * make goes through one of these methods. The goal is twofold:
 *
 *  1. Threading correctness lives in one place. {@code IntegrationPipeline.main}
 *     is dispatched on the EDT by {@code ApplicationStarter}, and the
 *     dumb/smart, VFS-refresh, and write-action sequences are easy to get
 *     wrong from there. Centralizing them lets the rest of the code state
 *     intent (commit-and-reparse, run-write-command) without re-deriving the
 *     EDT-safe shape every time.
 *
 *  2. Implementation-only IntelliJ APIs (the {@code *.impl} packages) become
 *     an isolated dependency of one class instead of a recurring source
 *     dependency across the codebase. The 2024.x impl, for example, still
 *     calls {@code com.intellij.ide.impl.ProjectUtil.openOrImport} because
 *     there is no public-API equivalent that performs open-or-import on a
 *     directory without an {@code .idea} folder; that single coupling now
 *     lives in {@link IntelliJ2024PlatformFacade} and nowhere else.
 *
 * A no-op or in-memory test double is anticipated for Week 5 unit tests.
 */
public interface PlatformFacade {

    /** Open the project at {@code path}, importing it if {@code .idea} is absent. */
    Project openProject(Path path);

    /**
     * Close and dispose an open project. Must be called before opening the
     * next project in a multi-project run: {@code openOrImport} shows the
     * headless-unsafe "where would you like to open the project" prompt when
     * another project is already open, which throws on the pipeline's path.
     */
    void closeProject(Project project);

    /**
     * Block (in an EDT-safe way) until the project is in smart mode.
     *
     * On 2024.x's headless path the caller is typically the EDT itself, so
     * {@code DumbService.runWhenSmart} with a {@code .get()} from the EDT
     * deadlocks. The 2024 implementation degrades to draining already-
     * submitted dumb tasks, which is what the existing pipeline relies on.
     */
    void waitForSmartMode(Project project);

    /** Refresh the entire VFS without engaging the file watcher. */
    void refreshAllVfs();

    /**
     * Commit pending PSI documents and drain dumb-mode tasks so subsequent
     * PSI reads see a consistent view. This is the deterministic replacement
     * for the {@code commitAllDocuments + dumbServiceHandler} pair that was
     * scattered across the pipeline.
     */
    void commitAndReparse(Project project);

    /**
     * Run {@code action} inside a write command. The 2024 implementation
     * defers to {@code WriteCommandAction.runWriteCommandAction}.
     */
    void runWriteCommand(Project project, Runnable action);

    /**
     * Flush all in-memory document changes to disk. The dispatchers call
     * this after a replay/invert pass so the subsequent git operations see
     * the refactored content.
     */
    void saveAllDocuments();

    /** Run {@code action} inside a plain read action (no smart-mode wait). */
    <T> T runReadAction(Supplier<T> action);

    /** Run {@code action} inside a plain write action (no command wrapping). */
    void runWriteAction(Runnable action);

    /**
     * Run {@code action} on the EDT and wait for it. IntelliJ refactoring
     * processors schedule themselves this way; from the EDT-dispatched
     * headless path the call is synchronous and does not deadlock.
     */
    void invokeAndWait(Runnable action);

    /**
     * Close the usage view a just-executed rename/move refactoring may have
     * opened. Interactive-IDE residue; a no-op when nothing is open.
     */
    void closeActiveUsageView(Project project);

    /** True under IntelliJ's unit-test application; guards module/root math. */
    boolean isUnitTestMode();

    /**
     * The focused text editor, or {@code null} when none is open (the
     * normal case on the headless pipeline). Inline processors accept the
     * null.
     */
    Editor getSelectedTextEditor(Project project);

    /**
     * Evaluate {@code computation} inside a read action, waiting for smart
     * mode first if the index is dumb. This is the correct primitive for
     * every PSI read on the headless path: the pipeline runs immediately
     * after a git checkout, so the index is routinely dumb and a bare
     * {@code JavaPsiFacade.findClass} throws {@code IndexNotReadyException}.
     *
     * The 2024 implementation defers to
     * {@code DumbService.runReadActionInSmartMode(Computable)}, which is
     * synchronous and EDT-safe: in smart mode it just runs the computation
     * under a read action; in dumb mode it waits for the index (draining
     * submitted dumb tasks when called from the EDT) before computing.
     * Unlike {@code runWhenSmart} + {@code Future.get()}, it cannot deadlock
     * from the EDT-dispatched {@code ApplicationStarter} entry point.
     */
    <T> T runInSmartReadAction(Project project, Supplier<T> computation);
}
