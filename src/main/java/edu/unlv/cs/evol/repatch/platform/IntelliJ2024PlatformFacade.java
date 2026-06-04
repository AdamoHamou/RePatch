package edu.unlv.cs.evol.repatch.platform;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.usages.UsageView;
import com.intellij.usages.UsageViewManager;

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

    @Override
    public void waitForSmartMode(Project project) {
        if (DumbService.isDumb(project)) {
            // From the EDT, runWhenSmart + .get() deadlocks because the smart
            // callback also targets the EDT. completeJustSubmittedTasks only
            // drains already-submitted indexing work, but that is what the
            // existing pipeline depends on and matches the prior behavior.
            DumbService.getInstance(project).completeJustSubmittedTasks();
        }
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

    /**
     * {@code runReadActionInSmartMode} is the platform-blessed way to do a
     * PSI read that must not observe a dumb index: synchronous, safe from
     * the EDT (it drains submitted dumb tasks rather than scheduling a
     * smart-mode callback that could never run), and it wraps the
     * computation in a read action. Do not replace this with
     * {@code runWhenSmart} + {@code Future.get()} — that deadlocks on the
     * EDT-dispatched headless path.
     */
    @Override
    public <T> T runInSmartReadAction(Project project, Supplier<T> computation) {
        return DumbService.getInstance(project).runReadActionInSmartMode(computation::get);
    }
}
