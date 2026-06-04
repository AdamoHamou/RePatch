package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Test double for {@link PlatformFacade} (Week 4 / Workstream 4C).
 *
 * Threading and synchronization primitives degrade to direct, synchronous
 * execution — correct in light-fixture tests, where the test runs on the
 * EDT with implicit read access and IntelliJ refactoring processors take
 * their own write actions. IDE-only surfaces (project opening, editors,
 * usage views) are stubbed to safe defaults. Counters record the
 * synchronization calls so contract tests can assert the
 * {@code RefactoringExecutionService} performed its post-operation steps.
 */
public class InMemoryPlatformFacade implements PlatformFacade {

    public int commitAndReparseCount;
    public int refreshAllVfsCount;
    public int saveAllDocumentsCount;
    public int waitForSmartModeCount;

    @Override
    public Project openProject(Path path) {
        throw new UnsupportedOperationException("InMemoryPlatformFacade cannot open projects");
    }

    @Override
    public void waitForSmartMode(Project project) {
        waitForSmartModeCount++;
    }

    @Override
    public void refreshAllVfs() {
        refreshAllVfsCount++;
    }

    @Override
    public void commitAndReparse(Project project) {
        commitAndReparseCount++;
    }

    @Override
    public void runWriteCommand(Project project, Runnable action) {
        action.run();
    }

    @Override
    public void saveAllDocuments() {
        saveAllDocumentsCount++;
    }

    @Override
    public <T> T runReadAction(Supplier<T> action) {
        return action.get();
    }

    @Override
    public void runWriteAction(Runnable action) {
        action.run();
    }

    @Override
    public void invokeAndWait(Runnable action) {
        action.run();
    }

    @Override
    public void closeActiveUsageView(Project project) {
        // No usage views outside the IDE.
    }

    @Override
    public boolean isUnitTestMode() {
        return true;
    }

    @Override
    public Editor getSelectedTextEditor(Project project) {
        return null;
    }

    @Override
    public <T> T runInSmartReadAction(Project project, Supplier<T> computation) {
        return computation.get();
    }
}
