package edu.unlv.cs.evol.repatch.platform;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.jps.model.serialization.PathMacroUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Module/source-root math carved out of {@code Utils} per Week 4 /
 * Workstream 4B: {@code addSourceRoot} and its content-entry helpers,
 * unchanged in behavior. 18 of the 23 invert/replay operation classes call
 * this before resolving PSI, because the kafka-style fixture checkouts have
 * no {@code .idea} and the file being refactored may live under a source
 * root the project model has not registered yet.
 *
 * Threading (read/write actions, dumb-task draining) goes through
 * {@link PlatformFacade}; the module and root APIs themselves
 * ({@code ModuleManager}, {@code ModifiableRootModel}) are this service's
 * domain, same convention as {@link PsiSearchService}.
 */
public final class ProjectRootsService {

    private final PlatformFacade platform;
    private final IndexingService indexing;

    /** Directory names never worth descending into when scanning for source roots. */
    private static final Set<String> PRUNE_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".gradle", "build", "out", "target", "bin", "node_modules"));

    /** Depth guard for the source-root scan so a pathological tree can't run away. */
    private static final int MAX_SCAN_DEPTH = 12;

    public ProjectRootsService(PlatformFacade platform) {
        this.platform = platform;
        this.indexing = new IndexingService(platform);
    }

    /**
     * Provision the conventional Java source roots across a freshly opened checkout.
     *
     * <p>WHY THIS EXISTS: the integration pipeline opens a bare git checkout that has no
     * {@code .idea} folder and no Gradle/Maven import. On IntelliJ 2024.x that comes up as a
     * single hollow module with <b>zero source roots</b>. The PSI-diag run (verdict: hypothesis
     * #1, unanimous — 234/234 {@code findClass==null} with {@code dumb=false},
     * {@code module 'linkedin' sourceRoots=0} every time) proved this is why every replay/invert
     * operation silently no-ops: {@code JavaPsiFacade.findClass} can't resolve a FQN with no
     * source root, so each op hits its {@code if (psiClass == null) return;} guard and the
     * refactoring is skipped, producing a wrong merge.
     *
     * <p>The reactive {@link #addSourceRoot} (called per-operation) was insufficient on the kafka
     * fixture — it never managed to register a root. This method runs ONCE at project open and
     * proactively registers every {@code **}{@code /src/main/java} and {@code **}{@code /src/test/java}
     * directory in the checkout as a source folder on the project's module, which is exactly what a
     * real Gradle import would have produced (minus the per-module split). After this, FQN
     * resolution works because each source root maps its package tree.
     *
     * <p>Threading mirrors {@link #addSourceRoot}: obtain the modifiable model in a read action,
     * mutate it, commit in a write action, then drain dumb tasks so the new roots are indexed.
     */
    public void provisionSourceRoots(Project project) {
        if (platform.isUnitTestMode()) {
            return;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            System.out.println("[RePatch-DIAG] provisionSourceRoots: basePath=null, skipped");
            return;
        }
        File baseDir = new File(basePath);
        VirtualFile projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(baseDir);
        if (projectRoot == null) {
            System.out.println("[RePatch-DIAG] provisionSourceRoots: projectRoot VFS null for " + basePath);
            return;
        }

        List<File> mainRoots = new ArrayList<>();
        List<File> testRoots = new ArrayList<>();
        collectSourceRoots(baseDir, mainRoots, testRoots, 0);

        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length == 0) {
            System.out.println("[RePatch-DIAG] provisionSourceRoots: no modules in project " + basePath);
            return;
        }
        // The auto-opened checkout is a single hollow module; register everything onto it.
        Module module = modules[0];

        ModifiableRootModel rootModel = platform.runReadAction(
                () -> ModuleRootManager.getInstance(module).getModifiableModel());
        ContentEntry contentEntry = findOrCreateContentEntry(rootModel, projectRoot);
        if (contentEntry == null) {
            platform.runWriteAction(rootModel::dispose);
            System.out.println("[RePatch-DIAG] provisionSourceRoots: could not obtain a content entry for "
                    + projectRoot.getPath());
            return;
        }

        Set<String> existing = new HashSet<>();
        for (VirtualFile f : contentEntry.getSourceFolderFiles()) {
            existing.add(f.getPath());
        }
        int added = addRoots(contentEntry, mainRoots, false, existing)
                + addRoots(contentEntry, testRoots, true, existing);

        if (added == 0) {
            platform.runWriteAction(rootModel::dispose);
        } else {
            platform.runWriteAction(rootModel::commit);
            indexing.drainDumbTasks(project);
        }
        System.out.println("[RePatch-DIAG] provisionSourceRoots: module='" + module.getName()
                + "' base=" + basePath + " discovered main=" + mainRoots.size()
                + " test=" + testRoots.size() + " newlyAdded=" + added);
    }

    /**
     * Recursively collect conventional Java source-root directories. A directory whose path ends
     * in {@code src/main/java} is a main root; {@code src/test/java} is a test root. We stop
     * descending once a root is found (nothing useful nests below it) and prune build/VCS dirs.
     */
    private void collectSourceRoots(File dir, List<File> mainRoots, List<File> testRoots, int depth) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            String name = child.getName();
            if (PRUNE_DIRS.contains(name) || name.startsWith(".")) {
                continue;
            }
            String p = child.getPath().replace(File.separatorChar, '/');
            if (p.endsWith("/src/main/java")) {
                mainRoots.add(child);
            } else if (p.endsWith("/src/test/java")) {
                testRoots.add(child);
            } else {
                collectSourceRoots(child, mainRoots, testRoots, depth + 1);
            }
        }
    }

    /**
     * Find a content entry that can host source folders under {@code projectRoot}: an exact match,
     * or an existing content root that already contains the checkout. Only when neither exists do we
     * add a new content entry — calling {@code addContentEntry} when an intersecting root is already
     * present throws "content roots cannot intersect".
     */
    private ContentEntry findOrCreateContentEntry(ModifiableRootModel rootModel, VirtualFile projectRoot) {
        ContentEntry ancestor = null;
        for (ContentEntry ce : rootModel.getContentEntries()) {
            VirtualFile f = ce.getFile();
            if (f == null) {
                continue;
            }
            if (projectRoot.getPath().equals(f.getPath())) {
                return ce;
            }
            if (projectRoot.getPath().startsWith(f.getPath() + "/")) {
                ancestor = ce; // an existing content root already containing the checkout
            }
        }
        if (ancestor != null) {
            return ancestor;
        }
        // Hollow module with no content root for the checkout — add one so source folders attach.
        return rootModel.addContentEntry(projectRoot);
    }

    /** Register each directory as a source folder, skipping any already present. Returns count added. */
    private int addRoots(ContentEntry contentEntry, List<File> roots, boolean isTest, Set<String> existing) {
        int added = 0;
        for (File root : roots) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root);
            if (vf == null || existing.contains(vf.getPath())) {
                continue;
            }
            contentEntry.addSourceFolder(vf, isTest);
            existing.add(vf.getPath());
            added++;
        }
        return added;
    }

    /*
     * Use the file path to add the source root to the module if it is not already in the module.
     */
    public void addSourceRoot(Project project, String filePath, String filePackage) {
        // There are no modules or source roots in unit test mode
        if (platform.isUnitTestMode()) {
            return;
        }
        boolean isTestFolder = filePath.contains("test");

        String projectPath = project.getBasePath();
        String relativePath = projectPath + "/" + filePath;
        filePackage = filePackage.replaceAll("\\.", "/");
        filePackage = filePackage.substring(0, filePackage.lastIndexOf("/"));
        String path = "";
        try {
            path = relativePath.substring(0, relativePath.indexOf(filePackage));
        } catch (StringIndexOutOfBoundsException e) {
            path = getRelativePathOfSourceRoot(relativePath, project.getName());
        }
        path = path.substring(0, path.lastIndexOf("/"));
        File directory = new File(path);
        VirtualFile sourceVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory);
        if (sourceVirtualFile == null) {
            return;
        }
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        // Get the first module that does not depend on any other modules
        ArrayList<Module> modules = getModule(sourceVirtualFile, moduleManager.getModules(), path);
        if (modules == null) {
            return;
        }

        for (Module module : modules) {
            final ModifiableRootModel rootModel = platform.runReadAction(
                    () -> ModuleRootManager.getInstance(module).getModifiableModel());
            directory = new File(Objects.requireNonNull(PathMacroUtil.getModuleDir(module.getModuleFilePath())));
            VirtualFile moduleVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory);
            if (moduleVirtualFile == null) {
                try {
                    if (sourceVirtualFile.getCanonicalPath().contains(directory.getCanonicalPath())) {
                        VirtualFile tempVirtualFile = sourceVirtualFile;
                        while (!sourceVirtualFile.getCanonicalPath().equals(directory.getAbsolutePath())) {
                            tempVirtualFile = tempVirtualFile.getParent();
                        }
                        moduleVirtualFile = tempVirtualFile;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else if (!moduleVirtualFile.equals(sourceVirtualFile) &&
                    !moduleVirtualFile.getCanonicalPath().contains(Objects.requireNonNull(sourceVirtualFile.getCanonicalPath()))) {
                continue;
            }
            ContentEntry contentEntry = getContentEntry(moduleVirtualFile, rootModel);
            if (contentEntry == null) {
                continue;
            }
            if (checkIfSourceFolderExists(sourceVirtualFile, contentEntry)) {
                platform.runWriteAction(rootModel::dispose);
                return;
            } else {
                contentEntry.addSourceFolder(sourceVirtualFile, isTestFolder);
                platform.runWriteAction(rootModel::commit);
                indexing.drainDumbTasks(project);
                break;
            }
        }
    }

    /*
     * Get the relative path of the source root folder.
     */
    private String getRelativePathOfSourceRoot(String relativePath, String projectName) {
        // If the relative path contains java, then that's the source folder.
        if (relativePath.contains("java/")) {
            return relativePath.substring(0, relativePath.lastIndexOf("java/") + 4);
        }
        if (relativePath.contains("resources/")) {
            return relativePath.substring(0, relativePath.lastIndexOf("resources/") + 9);
        }
        // Get the project name
        String temp = relativePath.substring(relativePath.indexOf(projectName) + projectName.length());
        // If the relative path contains the project name a second time, use that as a source folder.
        if (temp.contains(projectName)) {
            return relativePath.substring(0, relativePath.lastIndexOf(projectName));
        }
        // Otherwise return the src directory
        else {
            return relativePath.substring(0, relativePath.indexOf("src/") + 3);
        }
    }

    /*
     * Get the module that the virtual file is in.
     */
    private ArrayList<Module> getModule(VirtualFile virtualFile, Module[] modules, String path) {
        ArrayList<Module> potentialModules = new ArrayList<>();
        for (Module module : modules) {
            VirtualFile moduleFile = module.getModuleFile();
            String filePath = module.getModuleFilePath();
            filePath = filePath.substring(0, filePath.lastIndexOf("/"));
            // Return the module that we need
            if (filePath.equals(path)) {
                potentialModules.add(module);
                return potentialModules;
            }
            if (moduleFile == null) {
                continue;
            }
            VirtualFile moduleFileParent = moduleFile.getParent();
            // Get the src directory
            VirtualFile virtualFileParent = virtualFile.getParent();
            // If the src directory and .iml file are in the same module
            if (moduleFileParent.equals(virtualFileParent.getParent())) {
                potentialModules.add(module);
            }
        }
        potentialModules.addAll(Arrays.asList(modules));
        return potentialModules;
    }

    /*
     * Get the content entry in the specified module.
     */
    private ContentEntry getContentEntry(VirtualFile moduleVirtualFile, ModifiableRootModel rootModel) {
        for (ContentEntry contentEntry : rootModel.getContentEntries()) {
            if (contentEntry == null) {
                continue;
            }
            if (contentEntry.getFile() == null) {
                continue;
            }
            if (contentEntry.getFile().equals(moduleVirtualFile)) {
                return contentEntry;
            }
        }
        return null;
    }

    /*
     * Check if the virtual file already exists as a source folder to avoid unnecessary indexing.
     */
    private boolean checkIfSourceFolderExists(VirtualFile sourceVirtualFile, ContentEntry contentEntry) {
        VirtualFile[] sourceFolderFiles = contentEntry.getSourceFolderFiles();
        for (VirtualFile sourceFolderFile : sourceFolderFiles) {
            if (sourceVirtualFile.equals(sourceFolderFile)) {
                return true;
            }
        }
        return false;
    }
}
