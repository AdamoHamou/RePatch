package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.project.Project;

/**
 * Consolidated VFS + PSI synchronization routine that the pipeline relies on
 * after every git checkout, write, or refactoring replay.
 *
 * Before this service existed, callers spelled the sequence out by hand at
 * each site:
 *
 *     Utils.refreshVFS();
 *     Utils.reparsePsiFiles(project);
 *     Utils.dumbServiceHandler(project);
 *
 * The repetition was both noisy and easy to get wrong (some sites omitted the
 * VFS refresh, others called dumbServiceHandler before commitAllDocuments).
 * {@link #synchronize(Project)} is the single deterministic routine; new code
 * should call it instead of stitching the three primitives together.
 *
 * Per Week 3 / Workstream 3C, this service depends on {@link PlatformFacade}
 * and never on the IntelliJ runtime directly.
 */
public final class VfsSyncService {

    private final PlatformFacade platform;

    public VfsSyncService(PlatformFacade platform) {
        this.platform = platform;
    }

    /**
     * Bring the VFS and PSI views into a consistent post-checkout state:
     * refresh the VFS, commit pending PSI documents, and drain dumb-mode
     * tasks so subsequent reads see fresh content.
     */
    public void synchronize(Project project) {
        platform.refreshAllVfs();
        platform.commitAndReparse(project);
    }

    /**
     * The commit-and-reparse half without the VFS refresh. Use when the
     * caller has not touched files on disk and only needs the PSI to catch
     * up with in-memory document edits.
     */
    public void commitAndReparse(Project project) {
        platform.commitAndReparse(project);
    }
}
