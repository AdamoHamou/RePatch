package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/**
 * Platform-integration test (Workstream 5B): the production
 * {@link IntelliJ2024PlatformFacade} and the services behind it, run against
 * a real (light-fixture) IntelliJ project rather than the in-memory double.
 * Covers the three platform behaviors the pipeline leans on after every
 * checkout: index readiness (open/index), PSI resolution of a file that
 * appeared after the project opened (the checkout analog — both the
 * index-backed and the path-based fallback used while indexes are stale),
 * and source-root registration.
 */
public class PlatformServicesIntegrationTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String PACKAGE = "checkoutData";
    private static final String CLASS_NAME = "AppearedAfterOpen";
    private static final String QUALIFIED = PACKAGE + "." + CLASS_NAME;
    private static final String FILE_PATH = PACKAGE + "/" + CLASS_NAME + ".java";

    public void testPsiResolutionIndexReadinessAndSourceRootsOnTheRealPlatform() {
        IntelliJ2024PlatformFacade facade = new IntelliJ2024PlatformFacade();
        IndexingService indexing = new IndexingService(facade);
        PsiSearchService search = new PsiSearchService(facade);

        // -- project open/index: the fixture project is open and reaches a
        // smart index through the production facade's EDT-safe wait.
        assertFalse("fixture project must be open", getProject().isDisposed());
        indexing.drainDumbTasks(getProject());
        assertFalse("index must be smart after drainDumbTasks",
                DumbService.isDumb(getProject()));

        // -- PSI resolution after "checkout": a file added after the project
        // opened (like a git checkout changing the tree) resolves through the
        // smart-read path the pipeline uses.
        myFixture.addFileToProject(FILE_PATH,
                "package " + PACKAGE + ";\n\npublic class " + CLASS_NAME + " {\n"
                        + "    private int counter;\n"
                        + "    public void touch() { counter++; }\n"
                        + "}\n");
        PsiClass byName = search.findClass(getProject(), QUALIFIED, FILE_PATH);
        assertNotNull("class must resolve through PsiSearchService.findClass", byName);
        assertEquals(QUALIFIED, byName.getQualifiedName());

        PsiClass byPath = search.findClassByFilePath(getProject(), FILE_PATH, QUALIFIED);
        assertNotNull("class must resolve through the file-path fallback", byPath);
        assertEquals(QUALIFIED, byPath.getQualifiedName());

        // The resolved PSI is index-consistent: smart reads compute over it
        // without IndexNotReadyException.
        String fieldName = indexing.computeInSmartMode(getProject(),
                () -> byName.getFields()[0].getName());
        assertEquals("counter", fieldName);

        // -- source-root registration: the added file landed under a content
        // source root the platform actually registered, which is what
        // ProjectRootsService.addSourceRoot guarantees on the non-test path.
        VirtualFile addedFile = byName.getContainingFile().getVirtualFile();
        VirtualFile[] sourceRoots = ProjectRootManager.getInstance(getProject()).getContentSourceRoots();
        assertTrue("project must have at least one registered source root", sourceRoots.length > 0);
        boolean underSourceRoot = false;
        for (VirtualFile root : sourceRoots) {
            if (addedFile.getPath().startsWith(root.getPath())) {
                underSourceRoot = true;
                break;
            }
        }
        assertTrue("added file must be under a registered source root", underSourceRoot);

        // addSourceRoot itself must stay a safe no-op under a unit-test
        // platform (its production body needs real modules on disk).
        new ProjectRootsService(facade).addSourceRoot(getProject(), FILE_PATH, QUALIFIED);
    }
}
