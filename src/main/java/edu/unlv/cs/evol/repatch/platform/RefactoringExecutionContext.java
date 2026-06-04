package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.project.Project;

/**
 * Bundle of the project plus the Week 3/4 adapter-layer services that every
 * replay/invert operation needs (Week 4 / Workstream 4A).
 *
 * The dispatchers ({@code InvertRefactorings}, {@code ReplayRefactorings})
 * receive one context and hand it to each operation, so operation classes
 * stop constructing their own {@code Utils} (which silently re-instantiated
 * the production facade) and never touch IntelliJ runtime objects outside
 * the adapter layer.
 */
public final class RefactoringExecutionContext {

    private final Project project;
    private final PlatformFacade platform;
    private final VfsSyncService vfs;
    private final PsiSearchService psiSearch;
    private final IndexingService indexing;

    public RefactoringExecutionContext(Project project, PlatformFacade platform) {
        this.project = project;
        this.platform = platform;
        this.vfs = new VfsSyncService(platform);
        this.psiSearch = new PsiSearchService(platform);
        this.indexing = new IndexingService(platform);
    }

    public Project getProject() {
        return project;
    }

    public PlatformFacade getPlatform() {
        return platform;
    }

    public VfsSyncService getVfs() {
        return vfs;
    }

    public PsiSearchService getPsiSearch() {
        return psiSearch;
    }

    public IndexingService getIndexing() {
        return indexing;
    }
}
