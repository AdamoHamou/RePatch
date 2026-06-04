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
import java.util.Objects;

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

    public ProjectRootsService(PlatformFacade platform) {
        this.platform = platform;
        this.indexing = new IndexingService(platform);
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
