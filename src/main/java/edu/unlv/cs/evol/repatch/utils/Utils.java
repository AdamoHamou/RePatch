package edu.unlv.cs.evol.repatch.utils;

import edu.unlv.cs.evol.repatch.refactoringObjects.*;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import com.intellij.analysis.AnalysisScope;
import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.DumbServiceImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.JavaPsiFacadeImpl;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.refactoring.memberPullUp.PullUpProcessor;
import com.intellij.refactoring.util.classMembers.MemberInfo;
import com.intellij.refactoring.util.duplicates.MethodDuplicatesHandler;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Query;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.jps.model.serialization.PathMacroUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public class Utils {
    Project project;

    public static final String CONFLICT_LEFT_BEGIN = "<<<<<<<";
    public static final String CONFLICT_RIGHT_END = ">>>>>>>";

    private static final boolean LOG_TO_FILE  = true;
    private static final String LOG_FILE = "log.txt";


    public Utils(Project project) {
        this.project = project;
    }

    /*
     * Runs a command such as "cp -r ..." or "git merge-files ..."
     */
    public static void runSystemCommand(String... commands) {
        try {
            ProcessBuilder pb = new ProcessBuilder(commands);
            Process p = pb.start();
//            p.waitFor(200, TimeUnit.SECONDS);
//            p.destroy();
            p.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /*
     * Like runSystemCommand, but runs inside the given working directory and
     * returns the process output. runSystemCommand inherits the IDE process's
     * cwd, which is the gradle launch directory — git commands aimed at the
     * evaluation clone must set the clone as cwd explicitly.
     */
    public static List<String> runSystemCommandInDir(File workingDir, String... commands) {
        List<String> output = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output;
    }

    public static void log(String projectName, Object message) {
        String timeStamp = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss z").format(new Date());
        String logMessage = timeStamp + " ";
        if (message instanceof String){
            logMessage += (String) message;
        } else if (message instanceof Exception) {
            logMessage += ((Exception) message).getMessage() + "\n";
            StringBuilder stackBuilder = new StringBuilder();
            StackTraceElement[] stackTraceElements = ((Exception) message).getStackTrace();
            for (int i = 0; i < stackTraceElements.length; i++) {
                StackTraceElement stackTraceElement = stackTraceElements[i];
                stackBuilder.append(stackTraceElement.toString());
                if (i < stackTraceElements.length - 1) stackBuilder.append("\n");
            }
            logMessage += stackBuilder.toString();
        } else {
            logMessage = message.toString();
        }
        System.out.println(logMessage);

        if (LOG_TO_FILE) {
            String logPath = LOG_FILE;
            if (projectName != null && !projectName.trim().equals("")) logPath = projectName;
            try {
                String path = System.getProperty("user.home") + "/temp/logs/";
                new File(path).mkdirs();
                Files.write(Paths.get(path + logPath), Arrays.asList(logMessage),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }



    public static void dumbServiceHandler(Project project) {
        if(DumbService.isDumb(project)) {
            DumbServiceImpl dumbService = DumbServiceImpl.getInstance(project);
            // Waits for the task to finish
            dumbService.completeJustSubmittedTasks();
            // completeJustSubmittedTasks only flushes indexing submitted from
            // the EDT; indexing triggered by background VFS refreshes is not
            // covered, and PSI lookups then fail with IndexNotReadyException
            // (invert AND replay silently no-oped on a repo small enough for
            // the pipeline to reach them before initial indexing finished).
            // The pipeline runs ON the EDT (ApplicationStarter), where
            // waitForSmartMode is forbidden — pump the event queue instead so
            // indexing can complete before PSI lookups run.
            if (ApplicationManager.getApplication().isDispatchThread()) {
                while (DumbService.isDumb(project)) {
                    IdeEventQueue.getInstance().flushQueue();
                }
            } else {
                dumbService.waitForSmartMode();
            }
        }
    }

    public static void refreshVFS() {
        VirtualFileManager vFM = VirtualFileManager.getInstance();
        vFM.refreshWithoutFileWatcher(false);
    }

    /*
     * Make one refactoring's edits fully visible to the next one's usage
     * search. Each rename edits documents and triggers async index updates;
     * without a settle point, refactoring N+1's findUsages races those
     * updates and nondeterministically misses usages (observed on 16954:
     * identical runs alternate between complete and incomplete inversions
     * of the same refactoring list, e.g. a usage in KafkaStreams.java found
     * in one run and missed in the next). Commit documents, drain the EDT
     * queue, then wait out any dumb mode the updates triggered.
     */
    public static void settleAfterPsiEdit(Project project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        if (ApplicationManager.getApplication().isDispatchThread()) {
            IdeEventQueue.getInstance().flushQueue();
        }
        dumbServiceHandler(project);
    }

    /*
     * Phase-entry gate: wait until the workspace stops moving. Content-root
     * recalculation after checkouts runs asynchronously (observed: 149 roots
     * at right-invert entry dropping to 108 by left-invert entry) and
     * rescopes usage searches while a phase runs. Require module and root
     * counts to hold stable across consecutive samples before proceeding,
     * then drain the dumb queue.
     */
    /*
     * The workspace model can be DESTROYED mid-run: the platform's JPS
     * synchronizer reacts to (real or VFS-phantom) changes in .idea/*.iml
     * files and re-applies the model from its view of the disk; in a losing
     * timing that apply is empty, and a project that had 43 modules drops to
     * 0 for the rest of the run (observed across cached, cold-cache, and
     * auto-import-disabled configurations). The on-disk files stay intact
     * throughout, so the same watcher that zeroed the model can restore it:
     * touch modules.xml, refresh it in the VFS, and pump until the reload
     * re-applies a non-empty model.
     */
    public static boolean healProjectModel(Project project) {
        try {
            String modulesXml = project.getBasePath() + "/.idea/modules.xml";
            File f = new File(modulesXml);
            if (!f.exists()) {
                System.out.println("-> Model heal: no modules.xml at " + modulesXml);
                return false;
            }
            // Passive heal (touch + VFS refresh + pump) does NOT work: the
            // JPS watcher either ignores the touch or re-applies empty again.
            // Worse, the IDE SAVES the destroyed state back to disk (an empty
            // modules.xml), so the on-disk truth is gone too. Restore the
            // model files from a pristine backup the IDE never writes to
            // (-Drepatch.modelBackupDir, provided by the run harness), then
            // load each .iml directly through ModuleManager — no platform
            // watcher involved.
            System.out.println("-> Model heal: modules gone, loading .imls directly from modules.xml");
            List<String> imlPaths = parseImlPaths(f);
            String backupDir = System.getProperty("repatch.modelBackupDir");
            long existing = imlPaths.stream().filter(p -> new File(p).exists()).count();
            // The platform deletes the .iml files of "removed" modules when
            // the zeroed model auto-saves (observed: modules.xml intact with
            // 43 entries, all 43 .imls gone from disk). Restore whenever any
            // model file is missing, not just when modules.xml is empty.
            if ((imlPaths.isEmpty() || existing < imlPaths.size()) && backupDir != null) {
                System.out.println("-> Model heal: " + existing + "/" + imlPaths.size()
                        + " imls on disk; restoring model files from " + backupDir);
                restoreModelFilesFromBackup(backupDir, project.getBasePath());
                imlPaths = parseImlPaths(f);
            }
            System.out.println("-> Model heal: " + imlPaths.size() + " iml entries in modules.xml, "
                    + imlPaths.stream().filter(p -> new File(p).exists()).count() + " on disk");
            int loaded = 0;
            for (String imlPath : imlPaths) {
                if (!new File(imlPath).exists()) {
                    continue;
                }
                try {
                    WriteAction.runAndWait(() ->
                            ModuleManager.getInstance(project).loadModule(Paths.get(imlPath)));
                    loaded++;
                } catch (Throwable perModule) {
                    System.out.println("-> Model heal: could not load " + imlPath + ": " + perModule);
                }
            }
            if (ApplicationManager.getApplication().isDispatchThread()) {
                IdeEventQueue.getInstance().flushQueue();
            }
            dumbServiceHandler(project);
            int m = ModuleManager.getInstance(project).getModules().length;
            System.out.println("-> Model heal " + (m > 0 ? "succeeded: " + m + " modules (" + loaded
                    + " imls loaded)" : "FAILED: still 0 modules after loading " + loaded + " imls"));
            return m > 0;
        } catch (Throwable t) {
            System.out.println("-> Model heal error: " + t);
            return false;
        }
    }

    private static List<String> parseImlPaths(File modulesXml) throws IOException {
        String xml = new String(Files.readAllBytes(modulesXml.toPath()));
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("filepath=\"([^\"]+)\"").matcher(xml);
        List<String> imlPaths = new ArrayList<>();
        String projectDir = modulesXml.getParentFile().getParent();
        while (matcher.find()) {
            imlPaths.add(matcher.group(1).replace("$PROJECT_DIR$", projectDir));
        }
        return imlPaths;
    }

    /*
     * Copy .idea/modules.xml, .idea/misc.xml and every *.iml (by relative
     * path) from the pristine backup tree over the project. Only model files
     * are touched — never source files.
     */
    private static void restoreModelFilesFromBackup(String backupDir, String projectDir) {
        try {
            java.nio.file.Path backup = Paths.get(backupDir);
            java.nio.file.Path target = Paths.get(projectDir);
            for (String ideaFile : new String[]{".idea/modules.xml", ".idea/misc.xml"}) {
                java.nio.file.Path src = backup.resolve(ideaFile);
                if (Files.exists(src)) {
                    Files.createDirectories(target.resolve(ideaFile).getParent());
                    Files.copy(src, target.resolve(ideaFile),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            final int[] copied = {0};
            Files.walk(backup)
                    .filter(p -> p.toString().endsWith(".iml"))
                    .forEach(p -> {
                        try {
                            java.nio.file.Path rel = backup.relativize(p);
                            Files.copy(p, target.resolve(rel),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            copied[0]++;
                        } catch (IOException ignored) {
                        }
                    });
            System.out.println("-> Model heal: restored " + copied[0] + " imls + .idea metadata from backup");
        } catch (Throwable t) {
            System.out.println("-> Model heal: backup restore failed: " + t);
        }
    }

    public static void waitForWorkspaceSettle(Project project) {
        long deadline = System.currentTimeMillis() + 120_000L;
        int stable = 0;
        int prevModules = -1;
        int prevRoots = -1;
        boolean healTried = false;
        while (stable < 3 && System.currentTimeMillis() < deadline) {
            int m = ModuleManager.getInstance(project).getModules().length;
            // A module-less project is never "settled" — it is the failure
            // state itself. Attempt one self-heal (forced JPS reload from the
            // intact on-disk files) before waiting any further.
            if (m == 0 && !healTried) {
                healTried = true;
                healProjectModel(project);
                m = ModuleManager.getInstance(project).getModules().length;
            }
            int r = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getContentRoots().length;
            if (m > 0 && m == prevModules && r == prevRoots) {
                stable++;
            } else {
                stable = 0;
            }
            prevModules = m;
            prevRoots = r;
            if (ApplicationManager.getApplication().isDispatchThread()) {
                IdeEventQueue.getInstance().flushQueue();
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        dumbServiceHandler(project);
    }

    /*
     * The JPS workspace model loads asynchronously after openOrImport and is
     * applied on the EDT at non-modal modality. The pipeline monopolizes the
     * EDT and later runs under modal contexts (progress, dumb-queue
     * processing), so when the model loses the initial race its application
     * starves FOREVER: 0 modules -> 0 content roots -> empty indexes -> every
     * PSI lookup null -> vacuous inversions. (Observed: flaked runs stay at
     * modules=0 for 20+ minutes while clean runs show 43 within seconds;
     * startupPassed=true in both.) Must be called right after project open,
     * BEFORE any modal phase exists, where pumping still dispatches the
     * model-application runnables.
     */
    public static void waitForProjectModel(Project project) {
        if (project == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 300_000L;
        int modules = ModuleManager.getInstance(project).getModules().length;
        while (modules == 0 && System.currentTimeMillis() < deadline) {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                IdeEventQueue.getInstance().flushQueue();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            modules = ModuleManager.getInstance(project).getModules().length;
        }
        System.out.println("-> Project model loaded: " + modules + " modules, "
                + com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getContentRoots().length
                + " content roots");
        dumbServiceHandler(project);
    }

    /*
     * Use the file path to add the source root to the module if it is not already in the module.
     */
    public void addSourceRoot(String filePath, String filePackage) {
        // There are no modules or source roots in unit test mode
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        boolean isTestFolder = filePath.contains("test");

        String projectPath = project.getBasePath();
        String relativePath = projectPath + "/" + filePath;
        //relativePath = getRelativePathOfSourceRoot(relativePath, project.getName());
        filePackage = filePackage.replaceAll("\\.", "/");
        filePackage = filePackage.substring(0, filePackage.lastIndexOf("/"));
        String path = "";
        try {
            path = relativePath.substring(0, relativePath.indexOf(filePackage));
        }
        catch(StringIndexOutOfBoundsException e) {
            path = getRelativePathOfSourceRoot(relativePath, project.getName());
        }
        path = path.substring(0, path.lastIndexOf("/"));
        File directory = new File(path);
        VirtualFile sourceVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory);
        if(sourceVirtualFile == null) {
            return;
        }
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        // Get the first module that does not depend on any other modules
        ArrayList<Module> modules = getModule(sourceVirtualFile, moduleManager.getModules(), path);
        if(modules == null) {
            return;
        }


        for(Module module : modules) {
            AtomicReference<ModifiableRootModel> rootModel = new AtomicReference<>();
            ReadAction.run(() -> {
                rootModel.set(ModuleRootManager.getInstance(module).getModifiableModel());
            });
            directory = new File(Objects.requireNonNull(PathMacroUtil.getModuleDir(module.getModuleFilePath())));
            VirtualFile moduleVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory);
            if(moduleVirtualFile == null) {
                try {
                    if(sourceVirtualFile.getCanonicalPath().contains(directory.getCanonicalPath())) {
                        VirtualFile tempVirtualFile = sourceVirtualFile;
                        while (!sourceVirtualFile.getCanonicalPath().equals(directory.getAbsolutePath())) {
                            tempVirtualFile = tempVirtualFile.getParent();
                        }
                        moduleVirtualFile = tempVirtualFile;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            else if(!moduleVirtualFile.equals(sourceVirtualFile) &&
                    !moduleVirtualFile.getCanonicalPath().contains(Objects.requireNonNull(sourceVirtualFile.getCanonicalPath()))) {
                continue;
            }
            ContentEntry contentEntry = getContentEntry(moduleVirtualFile, rootModel.get());
            if(contentEntry == null) {
                continue;
            }
            if (checkIfSourceFolderExists(sourceVirtualFile, contentEntry)) {
                WriteAction.run(rootModel.get()::dispose);
                return;
            }
            else {
                contentEntry.addSourceFolder(sourceVirtualFile, isTestFolder);
                WriteAction.run(rootModel.get()::commit);
                Utils.dumbServiceHandler(project);
                break;
            }
        }
    }

    /*
     * Get the relative path of the source root folder.
     */
    private String getRelativePathOfSourceRoot(String relativePath, String projectName) {
        // If the relative path contains java, then that's the source folder.
        if(relativePath.contains("java/")) {
            return relativePath.substring(0, relativePath.lastIndexOf("java/") + 4);
        }
        if(relativePath.contains("resources/")) {
            return relativePath.substring(0, relativePath.lastIndexOf("resources/") + 9);
        }
        // Get the project name
        String temp = relativePath.substring(relativePath.indexOf(projectName) + projectName.length());
        // If the relative path contains the project name a second time, use that as a source folder.
        if(temp.contains(projectName)) {
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
        for(Module module : modules) {
            VirtualFile moduleFile = module.getModuleFile();
            String filePath = module.getModuleFilePath();
            filePath = filePath.substring(0, filePath.lastIndexOf("/"));
            // Return the module that we need
            if(filePath.equals(path)) {
                potentialModules.add(module);
                return potentialModules;
            }
            if(moduleFile == null) {
                continue;
            }
            VirtualFile moduleFileParent = moduleFile.getParent();
            // Get the src directory
            VirtualFile virtualFileParent = virtualFile.getParent();
            // If the src directory and .iml file are in the same module
            if(moduleFileParent.equals(virtualFileParent.getParent())) {
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
        for(ContentEntry contentEntry : rootModel.getContentEntries()) {
            if(contentEntry == null) {
                continue;
            }
            if(contentEntry.getFile() == null) {
                continue;
            }
            if(contentEntry.getFile().equals(moduleVirtualFile)) {
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
        for(VirtualFile sourceFolderFile : sourceFolderFiles) {
            if(sourceVirtualFile.equals(sourceFolderFile)) {
                return true;
            }
        }
        return false;
    }

    public static void reparsePsiFiles(Project project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
    }

    public static boolean ifSameMethods(PsiMethod method, MethodSignatureObject methodSignature) {
        PsiParameter[] psiParameterList = method.getParameterList().getParameters();
        List<ParameterObject> parameters = methodSignature.getParameterList();
        String umlName = methodSignature.getName();
        String psiName = method.getName();
        int firstUMLParam = 0;
        // Check if the method names are the same
        if (!umlName.equals(psiName)) {
            return false;
        }
        // If the number of parameters are different, the methods are different
        // Subtract 1 from umlParameters because umlParameters includes return type
        if (!methodSignature.isConstructor()) {
            if (parameters.size() - 1 != psiParameterList.length) {
                return false;
            }
            PsiType psiReturnType = method.getReturnType();
            assert psiReturnType != null;
            String psiType = psiReturnType.getPresentableText();
            ParameterObject parameterObject = parameters.get(0);
            String parameterType = parameterObject.getType();
            // Check if the return types are the same
            if (!psiType.equals(parameterType)) {
                // Check if UML type is class type
                if (parameterType.contains(".")) {
                    parameterType = parameterType.substring(parameterType.lastIndexOf(".") + 1);
                    if (!parameterType.equals(psiType)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            firstUMLParam = 1;
        }
        else {
            if(parameters.size() != psiParameterList.length) {
                return false;
            }
        }
        // Check if the parameters are the same
        return parameterComparator(firstUMLParam, parameters, psiParameterList);
    }

    /*
     * Compare the parameters in the UML parameter list to the parameters in the PSI parameter list to see if
     * the method signatures are the same.
     */
    private static boolean parameterComparator(int firstUMLParam, List<ParameterObject> parameters,
                                        PsiParameter[] psiParameterList) {
        ParameterObject parameterObject;
        String umlType;
        String psiType;
        // Check if the parameters are the same
        for(int i = firstUMLParam; i < parameters.size(); i++) {
            int j = i - firstUMLParam;
            parameterObject = parameters.get(i);
            PsiParameter psiParameter = psiParameterList[j];
            umlType = parameterObject.getType();

            String parameterName = psiParameter.getName();
            psiType = psiParameter.getText();
            psiType = psiType.substring(0, psiType.lastIndexOf(parameterName) - 1);
            // If the parameter has the final modifier, remove it for comparison with UML parameter.
            if(psiParameter.hasModifierProperty(PsiModifier.FINAL)) {
                psiType = psiType.substring(psiType.indexOf("final ") + 6);
            }
            // Replace int... with int[] for comparison with RefMiner object
            if(psiType.contains("...")) {
                psiType = psiType.replace("...", "[]");
            }
            if(!umlType.equals(psiType)) {
                return false;
            }

        }
        return true;
    }

    public PsiClass getPsiClassByFilePath(String filePath, String qualifiedClass) {
        // Get the name of the java file without the path
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        PsiFile[] psiFiles = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.allScope(project));
        // If no files are found, give an error message for debugging
        if(psiFiles.length == 0) {
            System.out.println("FAILED HERE");
            System.out.println(filePath);
            return null;
        }
        for (PsiFile file : psiFiles) {
            String classPath = file.getVirtualFile().getPath();
            if(!classPath.contains(filePath)) {
                continue;
            }
            PsiJavaFile psiFile = (PsiJavaFile) file;
            // Get the classes in the file
            PsiClass[] jClasses = psiFile.getClasses();
            for (PsiClass it : jClasses) {
                // Find the class that the refactoring happens in
                if (Objects.equals(it.getQualifiedName(), qualifiedClass)) {
                    return it;
                }
                // Need to update tests to remove this
                if (ApplicationManager.getApplication().isUnitTestMode()) {
                    if(qualifiedClass.contains(Objects.requireNonNull(it.getName()))) {
                        return it;
                    }
                }
                PsiClass[] innerClasses = it.getInnerClasses();
                for (PsiClass innerIt : innerClasses) {
                    if (Objects.equals(innerIt.getQualifiedName(), qualifiedClass)) {
                        return innerIt;
                    }
                }
            }
            for(PsiClass it : jClasses) {
                String qName = it.getQualifiedName();
                assert qName != null;
                qName = qName.substring(qName.lastIndexOf(".") + 1);
                String otherName = qualifiedClass.substring(qualifiedClass.lastIndexOf(".") + 1);
                if(Objects.equals(qName, otherName)) {
                    return it;
                }
            }
        }
        return null;
    }

    public PsiClass getPsiClassFromClassAndFileNames(String className, String filePath) {
        PsiClass psiClass = findPsiClassOnce(className, filePath);
        if(psiClass != null) {
            return psiClass;
        }
        // Null can mean the class is truly absent OR that the initial indexing
        // scan has not caught up with the checked-out tree: the pipeline runs
        // on the EDT and can reach PSI lookups before the scan is even queued,
        // in which case isDumb() is still false and the indexes are simply
        // empty (every invert then silently no-ops — the vacuous-inversion
        // mode). The two are distinguishable: the file existing on disk while
        // FilenameIndex cannot see it proves index blindness, so refresh,
        // pump, and retry only in that state, bounded by a deadline.
        int rounds = 0;
        long deadline = System.currentTimeMillis() + 120_000L;
        while(psiClass == null && isIndexBlindTo(filePath) && System.currentTimeMillis() < deadline) {
            if(rounds == 0) {
                System.out.println("-> Index not ready for " + filePath + "; waiting for indexing to catch up");
            }
            // Diagnostic snapshot on a sparse schedule (rounds 0,1,2,4,8,...):
            // captures which layer is failing — VFS, PSI-by-path, or the
            // index query — plus dumb/startup state, to pin the flake's
            // mechanism instead of guessing at it.
            if(rounds <= 2 || (rounds & (rounds - 1)) == 0) {
                logIndexProbe(rounds, className, filePath);
            }
            rounds++;
            refreshVFS();
            reparsePsiFiles(project);
            dumbServiceHandler(project);
            if(ApplicationManager.getApplication().isDispatchThread()) {
                IdeEventQueue.getInstance().flushQueue();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            psiClass = findPsiClassOnce(className, filePath);
        }
        if(rounds > 0) {
            System.out.println("-> Index readiness wait ended after " + rounds + " rounds; "
                    + className + (psiClass != null ? " resolved" : " STILL unresolved"));
            if(psiClass == null) {
                logIndexProbe(rounds, className, filePath);
            }
        }
        return psiClass;
    }

    /*
     * One-line state snapshot of every layer involved in resolving a class,
     * from the raw VFS up to the index query. INDEXPROBE lines are grep bait
     * for run forensics.
     */
    private void logIndexProbe(int round, String className, String filePath) {
        StringBuilder sb = new StringBuilder("INDEXPROBE round=").append(round);
        try {
            sb.append(" modules=").append(ModuleManager.getInstance(project).getModules().length);
            sb.append(" contentRoots=").append(
                    com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getContentRoots().length);
            sb.append(" dumb=").append(DumbService.isDumb(project));
            try {
                sb.append(" startupPassed=").append(
                        com.intellij.ide.startup.StartupManagerEx.getInstanceEx(project).postStartupActivityPassed());
            } catch (Throwable t) {
                sb.append(" startupPassed=?").append(t.getClass().getSimpleName());
            }
            String absPath = project.getBasePath() + "/" + filePath;
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(absPath);
            if(vf == null) {
                vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath);
                sb.append(" vfs=").append(vf == null ? "MISSING" : "found-after-refresh");
            } else {
                sb.append(" vfs=").append(vf.isValid() ? "valid" : "INVALID");
            }
            if(vf != null) {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if(pf instanceof PsiJavaFile) {
                    PsiClass[] classes = ((PsiJavaFile) pf).getClasses();
                    boolean hit = false;
                    for(PsiClass c : classes) {
                        if(Objects.equals(c.getQualifiedName(), className)) { hit = true; break; }
                    }
                    sb.append(" psiByPath=").append(classes.length).append("classes,target=").append(hit);
                } else {
                    sb.append(" psiByPath=").append(pf == null ? "null" : pf.getClass().getSimpleName());
                }
                sb.append(" inProjectScope=").append(
                        GlobalSearchScope.projectScope(project).contains(vf));
            }
            String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
            try {
                sb.append(" filenameIdx=").append(
                        FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.allScope(project)).length);
            } catch (Throwable t) {
                sb.append(" filenameIdx=").append(t.getClass().getSimpleName());
            }
            try {
                sb.append(" findClass=").append(
                        new JavaPsiFacadeImpl(project).findClass(className, GlobalSearchScope.allScope(project)) != null);
            } catch (Throwable t) {
                sb.append(" findClass=").append(t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            sb.append(" probeError=").append(t);
        }
        System.out.println(sb);
    }

    private PsiClass findPsiClassOnce(String className, String filePath) {
        try {
            JavaPsiFacade jPF = new JavaPsiFacadeImpl(project);
            PsiClass psiClass = jPF.findClass(className, GlobalSearchScope.allScope((project)));
            // If the class isn't found, there might not have been a gradle file and we need to find the class another way
            if(psiClass == null) {
                psiClass = getPsiClassByFilePath(filePath, className);
            }
            return psiClass;
        } catch (com.intellij.openapi.project.IndexNotReadyException e) {
            return null;
        }
    }

    /*
     * True when the file exists in the working tree but the filename index
     * cannot see it — the signature of querying before initial indexing has
     * completed (a legitimately deleted/renamed file returns false and the
     * caller's null stands immediately).
     */
    private boolean isIndexBlindTo(String filePath) {
        File onDisk = new File(project.getBasePath(), filePath);
        if(!onDisk.exists()) {
            return false;
        }
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        try {
            return FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.allScope(project)).length == 0;
        } catch (com.intellij.openapi.project.IndexNotReadyException e) {
            return true;
        }
    }

    public static PsiMethod getPsiMethod(PsiClass psiClass, MethodSignatureObject methodSignatureObject) {
        PsiMethod[] methods = psiClass.getMethods();
        for(PsiMethod method : methods) {
            if(Utils.ifSameMethods(method, methodSignatureObject)) {
                return method;
            }
        }
        return null;
    }

    public static PsiParameter getPsiParameter(PsiMethod psiMethod, ParameterObject parameterObject) {
        // No need to compare types, two parameters in the same signature cannot have the same name,
        // so the type does not matter
        String parameterName = parameterObject.getName();
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        for(PsiParameter parameter : parameters) {
            String psiParameterName = parameter.getName();
            if(psiParameterName.equals(parameterName)) {
                return parameter;
            }
        }

        return null;
    }

    public static PsiField getPsiField(PsiClass psiClass, String fieldName) {
        PsiField[] fields = psiClass.getFields();
        for(PsiField field : fields) {
            if(field.getName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }


    /*
     * Format the text to remove new lines and spaces for comparing code fragments
     */
    public static String formatText(String text) {
        text = text.replaceAll(" ", "");
        text = text.replaceAll("\n", "");
        return text;
    }

    public static PsiJavaCodeReferenceElement getPsiReferenceExpressionsForExtractMethod(PsiMethod psiMethod, Project project) {
        RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
        // Create renameRefactoring to find usages of the extracted method
        RenameRefactoring renameRefactoring = factory.createRename(psiMethod, "method", false, true);
        UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
        for(UsageInfo usageInfo : refactoringUsages) {
            PsiElement element = usageInfo.getElement();
            if(usageInfo.getElement() instanceof PsiReferenceExpression) {
                if(usageInfo.getElement() instanceof PsiJavaCodeReferenceElement) {
                    return (PsiJavaCodeReferenceElement) element;
                }
            }
        }
        return null;
    }

    public void removeRefactoringsInConflictingFile(String path, String absolutePath, List<RefactoringObject> refactorings) throws ExecutionException, InterruptedException {
        if(!path.endsWith(".java")) {
            return;
        }
        List<Pair<Integer, Integer>> conflictingRegions = getConflictingRegions(absolutePath);
        for(Iterator<RefactoringObject> iterator = refactorings.iterator(); iterator.hasNext(); ) {
            RefactoringObject refactoring = iterator.next();
            if(refactoring instanceof InlineMethodObject || refactoring instanceof ExtractMethodObject) {
                // Never replay Extract/Inline into a file git could not auto-merge:
                // their replay operations are conflict-marker-blind, so re-applying
                // them on top of conflict markers fabricates conflicts that plain
                // git does not have. Their stored line numbers refer to the original
                // commit's text, so region-level precision is not meaningful here —
                // match on either endpoint of the refactoring instead.
                if (path.equals(refactoring.getOriginalFilePath())
                        || path.equals(refactoring.getDestinationFilePath())) {
                    System.out.println("-> Pruned " + refactoring.getRefactoringType()
                            + " from replay: conflicting file " + path);
                    iterator.remove();
                }
                continue;
            }

            if (!path.equals(refactoring.getOriginalFilePath())) {
                continue;
            }

            runWhenSmartWithFuture(project, () -> {
                Utils.refreshVFS();
                Utils.reparsePsiFiles(project);
                Utils.dumbServiceHandler(project);
            });

            setBoundaries(refactoring);
            if (!checkReplayRefactoring(refactoring, conflictingRegions)) {
                System.out.println("-> Pruned " + refactoring.getRefactoringType()
                        + " from replay: overlaps conflict region in " + path);
                iterator.remove();
            }
        }
    }

    public static CompletableFuture<Void> runWhenSmartWithFuture(Project project, Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        DumbService.getInstance(project).runWhenSmart((DumbAwareRunnable) () -> {
            try {
                System.out.println("Indexing completed. Executing task...");
                task.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private List<Pair<Integer, Integer>> getConflictingRegions(String path) {
        File file = new File(path);
        List<Pair<Integer, Integer>> conflictingRegions = new ArrayList<>();
        int startLine = 0;
        int endLine = 0;
        int counter = 0;
        try {
            InputStream stream = file.toURI().toURL().openStream();

        for(String line : getLinesFromInputStream(stream)) {
            counter++;
            if(line.contains(CONFLICT_LEFT_BEGIN)) {
                startLine = counter;
            }
            else if(line.contains(CONFLICT_RIGHT_END)) {
                endLine = counter;
                conflictingRegions.add(Pair.of(startLine, endLine));
            }
        }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return conflictingRegions;
    }

    /*
     * Get each line from the input stream containing the IntelliMerge dataset.
     */
    public static ArrayList<String> getLinesFromInputStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        ArrayList<String> lines = new ArrayList<>();
        while(reader.ready()) {
            lines.add(reader.readLine());
        }
        return lines;
    }

    private boolean checkReplayRefactoring(RefactoringObject refactoring, List<Pair<Integer, Integer>> conflictingRegions) {
        int refStartLine = refactoring.getStartLine();
        int refEndLine = refactoring.getEndLine();
        // Types without boundary support report 0/0; their edit surface is
        // unknown, so keep the conservative historical behavior: never replay
        // them into a file that has conflict regions.
        if(refStartLine == 0 && refEndLine == 0) {
            return false;
        }
        for(Pair<Integer,Integer> conflictingRegion : conflictingRegions) {
            int conflictingStartLine = conflictingRegion.getLeft();
            int conflictingEndLine = conflictingRegion.getRight();
            // Disjoint ranges cannot interact; check the next region.
            if(refEndLine < conflictingStartLine || refStartLine > conflictingEndLine) {
                continue;
            }
            // The conflict region sits strictly inside the refactoring's range:
            // safe for method/class renames (they edit the signature, not the
            // body), but NOT for parameter renames, whose usage edits span the
            // whole method body including the conflicted lines.
            if(refStartLine < conflictingStartLine && refEndLine > conflictingEndLine
                    && !(refactoring instanceof RenameParameterObject)) {
                continue;
            }
            // Any other overlap: do not replay on top of conflict markers.
            return false;
        }
        // If the regions are not related or the region is within the method or class, replay
        return true;
    }

    /*
     * Get the boundaries for the given PSI class
     */
    private void setBoundaries(RefactoringObject ref) {
        PsiElement psiElement;
        if(ref instanceof MoveRenameMethodObject) {
            String filePath = ref.getOriginalFilePath();
            String className = ((MoveRenameMethodObject) ref).getOriginalClassName();
            PsiClass psiClass = getPsiClassFromClassAndFileNames(className, filePath);
            if(psiClass == null) {
                return;
            }
            PsiMethod psiMethod = getPsiMethod(psiClass, ((MoveRenameMethodObject) ref).getOriginalMethodSignature());
            if(psiMethod == null) {
                return;
            }
            psiElement = psiMethod;
        }
        else if(ref instanceof MoveRenameClassObject) {
            String filePath = ref.getOriginalFilePath();
            String className = ((MoveRenameClassObject) ref).getOriginalClassObject().getClassName();
            PsiClass psiClass = getPsiClassFromClassAndFileNames(className, filePath);
            if(psiClass == null) {
                return;
            }
            psiElement = psiClass;
        }
        else if(ref instanceof RenameParameterObject) {
            // A parameter rename edits only its containing method (parameter
            // scope is method-local), so the method's range is the replay's
            // edit surface. Without boundaries the 0/0 default made every
            // rename-parameter in a conflicting file look overlapping.
            String filePath = ref.getOriginalFilePath();
            String className = ((RenameParameterObject) ref).getOriginalClassName();
            PsiClass psiClass = getPsiClassFromClassAndFileNames(className, filePath);
            if(psiClass == null) {
                return;
            }
            PsiMethod psiMethod = getPsiMethod(psiClass, ((RenameParameterObject) ref).getOriginalMethodSignature());
            if(psiMethod == null) {
                return;
            }
            psiElement = psiMethod;
        }
        else {
            return;
        }

        try {
            TextRange range = psiElement.getTextRange();
            Document document = PsiDocumentManager.getInstance(project).getCachedDocument(psiElement.getContainingFile());
            if (document != null) {
                ref.setStartLine(document.getLineNumber(range.getStartOffset()));
                ref.setEndLine(document.getLineNumber(range.getEndOffset()));
            }
        }
        catch(NullPointerException e) {
            e.printStackTrace();
        }
    }

    public MemberInfo[] getMembersToPullUp(List<com.intellij.openapi.util.Pair<String, String>> subClasses, MethodSignatureObject methodObject) {
        MemberInfo[] psiMembers = new MemberInfo[subClasses.size()];

        int i = 0;
        for(com.intellij.openapi.util.Pair<String, String> subClass : subClasses) {
            String className = subClass.getFirst();
            String fileName = subClass.getSecond();
            PsiClass psiClass = getPsiClassFromClassAndFileNames(className, fileName);
            if(psiClass == null) {
                continue;
            }
            PsiMethod psiMethod = getPsiMethod(psiClass, methodObject);
            if(psiMethod == null) {
                continue;
            }
            psiMembers[i] = new MemberInfo(psiMethod);
            i++;
        }

        return  psiMembers;
    }

    public MemberInfo[] getFieldsToPullUp(List<com.intellij.openapi.util.Pair<String, String>> subClasses, String fieldName) {
        MemberInfo[] psiMembers = new MemberInfo[subClasses.size()];

        int i = 0;
        for(com.intellij.openapi.util.Pair<String, String> subClass : subClasses) {
            String className = subClass.getFirst();
            String fileName = subClass.getSecond();
            PsiClass psiClass = getPsiClassFromClassAndFileNames(className, fileName);
            if(psiClass == null) {
                continue;
            }
            PsiField psiField = getPsiField(psiClass, fieldName);
            if(psiField == null) {
                continue;
            }
            psiMembers[i] = new MemberInfo(psiField);
            i++;
        }

        return  psiMembers;
    }



    public void processMethodsDuplicates(PullUpProcessor pullUpProcessor) {
        PsiClass myTargetSuperClass = pullUpProcessor.getTargetClass();
        Set<PsiMember> myMembersAfterMove = pullUpProcessor.getMovedMembers();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> ApplicationManager.getApplication().runReadAction(() -> {
            if (!myTargetSuperClass.isValid()) return;
            final Query<PsiClass> search = ClassInheritorsSearch.search(myTargetSuperClass);
            final Set<VirtualFile> hierarchyFiles = new HashSet<>();
            for (PsiClass aClass : search) {
                final PsiFile containingFile = aClass.getContainingFile();
                if (containingFile != null) {
                    final VirtualFile virtualFile = containingFile.getVirtualFile();
                    if (virtualFile != null) {
                        hierarchyFiles.add(virtualFile);
                    }
                }
            }
            final Set<PsiMember> methodsToSearchDuplicates = new HashSet<>();
            for (PsiMember psiMember : myMembersAfterMove) {
                if (psiMember instanceof PsiMethod && psiMember.isValid() && ((PsiMethod)psiMember).getBody() != null) {
                    methodsToSearchDuplicates.add(psiMember);
                }
            }

            MethodDuplicatesHandler.invokeOnScope(project, methodsToSearchDuplicates, new AnalysisScope(project, hierarchyFiles), true);
        }), MethodDuplicatesHandler.getRefactoringName(), true, project);
    }


}

