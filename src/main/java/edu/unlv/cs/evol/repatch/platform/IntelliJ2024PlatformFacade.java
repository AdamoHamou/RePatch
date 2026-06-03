package edu.unlv.cs.evol.repatch.platform;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDocumentManager;

import java.nio.file.Path;

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
}
