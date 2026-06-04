package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.project.Project;

import java.util.function.Supplier;

/**
 * Index/dumb-mode coordination carved out of {@code Utils} per Week 4 /
 * Workstream 4B. Replaces two former idioms:
 *
 *  - {@code Utils.dumbServiceHandler(project)} — the drain-submitted-dumb-
 *    tasks call sprinkled before PSI reads; now {@link #drainDumbTasks}.
 *  - {@code Utils.runWhenSmartWithFuture(project, task)} — the RefMerge
 *    idiom that schedules a smart-mode callback and hands back a future.
 *    On RePatch's EDT-dispatched headless path the callback also targets
 *    the EDT, so any {@code .get()} deadlocks and a fire-and-forget call
 *    runs at an unpredictable later time. There is deliberately no
 *    replacement that returns a future: callers that need a result in
 *    smart mode use the synchronous {@link #computeInSmartMode}.
 *
 * Like {@link VfsSyncService}, this service depends only on
 * {@link PlatformFacade} and never on the IntelliJ runtime directly.
 */
public final class IndexingService {

    private final PlatformFacade platform;

    public IndexingService(PlatformFacade platform) {
        this.platform = platform;
    }

    /**
     * Drain already-submitted dumb-mode tasks if the project is dumb.
     * EDT-safe; see {@link PlatformFacade#waitForSmartMode} for limits.
     */
    public void drainDumbTasks(Project project) {
        platform.waitForSmartMode(project);
    }

    /**
     * Evaluate {@code computation} under a read action once the index is
     * smart. Synchronous and EDT-safe — the correct primitive for any PSI
     * read that must not observe a dumb index.
     */
    public <T> T computeInSmartMode(Project project, Supplier<T> computation) {
        return platform.runInSmartReadAction(project, computation);
    }
}
