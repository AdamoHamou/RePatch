package edu.unlv.cs.evol.repatch.platform;

import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.usages.UsageView;
import com.intellij.usages.UsageViewManager;
import edu.unlv.cs.evol.repatch.utils.TimeoutPolicy;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Production PlatformFacade for IntelliJ 2024.3.x running headlessly under
 * {@code ApplicationStarter} (the EDT-dispatched entry point).
 *
 * This class deliberately concentrates every direct IntelliJ runtime call
 * the pipeline needs into a single file. The only remaining
 * {@code com.intellij.*.impl} dependency in the production tree is
 * {@link ProjectUtil#openOrImport} below; it is documented inline and
 * scoped to this one class.
 */
public final class IntelliJ2024PlatformFacade implements PlatformFacade {

    private final TimeoutPolicy timeouts;

    public IntelliJ2024PlatformFacade() {
        this(TimeoutPolicy.fromSystemProperties());
    }

    public IntelliJ2024PlatformFacade(TimeoutPolicy timeouts) {
        this.timeouts = timeouts;
    }

    /**
     * 2024.x has no public-API "open or import" entry point that handles a
     * directory without an existing {@code .idea}. {@code ProjectManager.
     * loadAndOpenProject} requires an already-imported project; the
     * recommended replacement {@code ProjectManagerEx.openProject} in
     * {@code openapi.project.ex} is also a borderline-public surface. The
     * one surface that reliably imports kafka-style directories from a bare
     * checkout (which the test fixture wipes {@code .idea} from on every
     * run) is still {@code ide.impl.ProjectUtil.openOrImport}. Replacing it
     * is broader migration work; isolating it here keeps the rest of the
     * codebase free of {@code *.impl} imports.
     */
    @Override
    public Project openProject(Path path) {
        return ProjectUtil.openOrImport(path, null, false);
    }

    /**
     * Close and dispose a project. {@code closeAndDispose} is public API and,
     * on the pipeline's EDT-bound headless path, runs synchronously. Closing
     * the prior project before opening the next is what keeps
     * {@code openOrImport} from showing the "where would you like to open the
     * project" prompt (which throws headlessly) on a multi-project run.
     */
    @Override
    public void closeProject(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        ProjectManager.getInstance().closeAndDispose(project);
    }

    @Override
    public void waitForSmartMode(Project project) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            DumbService.getInstance(project).waitForSmartMode();
            return;
        }
        pumpEventsUntilSmart(project);
    }

    /**
     * Reach smart mode from the EDT, where every blocking primitive is
     * either a deadlock or a no-op:
     *
     *  - {@code runWhenSmart} + {@code Future.get()} deadlocks (the callback
     *    targets the EDT we are blocking);
     *  - {@code DumbService.waitForSmartMode} asserts off-EDT;
     *  - {@code runReadActionInSmartMode} silently degrades to an immediate
     *    read when called on the EDT, because it cannot wait there;
     *  - {@code completeJustSubmittedTasks} alone drains only the dumb tasks
     *    submitted so far.
     *
     * The headless pipeline's {@code main} occupies the EDT for the whole
     * run, so indexing work queued via {@code invokeLater} (task
     * submission, dumb-mode exit events) can never execute behind it. The
     * only correct EDT shape is to pump the event queue — letting the
     * queued indexing machinery actually run — and drain submitted dumb
     * tasks, until the project reports smart. {@code IdeEventQueue} is a
     * platform-internal surface, accepted here for the same reason as
     * {@code ide.impl.ProjectUtil}: there is no public-API equivalent for
     * this headless-on-EDT situation, and the coupling is confined to this
     * class.
     */
    private void pumpEventsUntilSmart(Project project) {
        DumbService dumbService = DumbService.getInstance(project);
        long deadline = System.currentTimeMillis() + timeouts.smartModeWaitMs();
        // Flush at least once before trusting isDumb: a dumb-mode start may
        // be sitting in the queue, not yet dispatched, in which case the
        // project still reports smart against a stale index.
        do {
            IdeEventQueue.getInstance().flushQueue();
            dumbService.completeJustSubmittedTasks();
            if (!DumbService.isDumb(project)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                System.out.println("[PlatformFacade] project still dumb after "
                        + timeouts.smartModeWaitMs() + "ms of event pumping; proceeding anyway");
                return;
            }
            // Indexing is proceeding on background threads; yield briefly
            // instead of spinning the EDT hot.
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (true);
    }

    @Override
    public void refreshAllVfs() {
        VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
    }

    @Override
    public void commitAndReparse(Project project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        waitForSmartMode(project);
    }

    @Override
    public void runWriteCommand(Project project, Runnable action) {
        WriteCommandAction.runWriteCommandAction(project, action);
    }

    @Override
    public void saveAllDocuments() {
        FileDocumentManager.getInstance().saveAllDocuments();
    }

    @Override
    public <T> T runReadAction(Supplier<T> action) {
        return ReadAction.compute(action::get);
    }

    @Override
    public void runWriteAction(Runnable action) {
        WriteAction.run(action::run);
    }

    @Override
    public void invokeAndWait(Runnable action) {
        ApplicationManager.getApplication().invokeAndWait(action);
    }

    @Override
    public void closeActiveUsageView(Project project) {
        UsageView usageView = UsageViewManager.getInstance(project).getSelectedUsageView();
        if (usageView != null) {
            usageView.close();
        }
    }

    @Override
    public boolean isUnitTestMode() {
        return ApplicationManager.getApplication().isUnitTestMode();
    }

    @Override
    public Editor getSelectedTextEditor(Project project) {
        return FileEditorManager.getInstance(project).getSelectedTextEditor();
    }

    /**
     * Off the EDT, {@code DumbService.runReadActionInSmartMode} is the
     * platform-blessed synchronous smart read. On the EDT it cannot wait
     * and silently degrades to an immediate read — which is how the
     * pipeline's post-checkout reads kept hitting
     * {@code IndexNotReadyException} even through this method — so the EDT
     * branch pumps the event queue to smart mode first and retries the
     * bounded read if dumb mode is re-entered mid-computation.
     */
    @Override
    public <T> T runInSmartReadAction(Project project, Supplier<T> computation) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            return DumbService.getInstance(project).runReadActionInSmartMode(computation::get);
        }
        int attempt = 0;
        while (true) {
            pumpEventsUntilSmart(project);
            try {
                return ReadAction.compute(computation::get);
            } catch (IndexNotReadyException e) {
                // Dumb mode re-entered between the wait and the read; pump
                // again. Give up after a few rounds rather than loop forever
                // against a wedged indexer.
                if (++attempt >= 5) {
                    throw e;
                }
            }
        }
    }
}
