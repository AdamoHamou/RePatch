package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.project.Project;

import java.nio.file.Path;

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
}
