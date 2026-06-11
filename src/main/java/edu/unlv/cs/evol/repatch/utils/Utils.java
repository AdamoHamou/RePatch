package edu.unlv.cs.evol.repatch.utils;

import edu.unlv.cs.evol.repatch.platform.IndexingService;
import edu.unlv.cs.evol.repatch.platform.IntelliJ2024PlatformFacade;
import edu.unlv.cs.evol.repatch.platform.ProjectRootsService;
import edu.unlv.cs.evol.repatch.platform.PsiSearchService;
import edu.unlv.cs.evol.repatch.platform.VfsSyncService;
import edu.unlv.cs.evol.repatch.refactoringObjects.*;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.MethodSignatureObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ParameterObject;
import com.intellij.analysis.AnalysisScope;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
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

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class Utils {
    Project project;
    private final VfsSyncService vfs;
    private final PsiSearchService psiSearch;
    private final IndexingService indexing;
    private final ProjectRootsService projectRoots;

    public static final String CONFLICT_LEFT_BEGIN = "<<<<<<<";
    public static final String CONFLICT_RIGHT_END = ">>>>>>>";


    public Utils(Project project) {
        this(project, new IntelliJ2024PlatformFacade());
    }

    private Utils(Project project, IntelliJ2024PlatformFacade platform) {
        this(project, new VfsSyncService(platform), new PsiSearchService(platform),
                new IndexingService(platform), new ProjectRootsService(platform));
    }

    public Utils(Project project, VfsSyncService vfs, PsiSearchService psiSearch,
                 IndexingService indexing, ProjectRootsService projectRoots) {
        this.project = project;
        this.vfs = vfs;
        this.psiSearch = psiSearch;
        this.indexing = indexing;
        this.projectRoots = projectRoots;
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

    /**
     * Retained entry point for the existing call sites; the logging logic now
     * lives in {@link LoggingService} (carved out so repatch + integration
     * share one implementation with levels and a per-scenario operation id).
     */
    public static void log(String projectName, Object message) {
        LoggingService.log(projectName, message);
    }



    public static void refreshVFS() {
        VirtualFileManager vFM = VirtualFileManager.getInstance();
        vFM.refreshWithoutFileWatcher(false);
    }

    /*
     * Use the file path to add the source root to the module if it is not already in the module.
     * Moved to ProjectRootsService (Week 4 / 4B); delegation kept for unmigrated operation classes.
     */
    public void addSourceRoot(String filePath, String filePackage) {
        projectRoots.addSourceRoot(project, filePath, filePackage);
    }

    public static void reparsePsiFiles(Project project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
    }

    // The PSI lookup family below moved to PsiSearchService (Week 4 / 4B).
    // These delegations keep the 30+ unmigrated invert/replay/matrix call
    // sites compiling; new code should depend on PsiSearchService directly.
    // The index-backed lookups now run inside a smart read action, which is
    // what eliminated the post-checkout IndexNotReadyException flood.

    public static boolean ifSameMethods(PsiMethod method, MethodSignatureObject methodSignature) {
        return PsiSearchService.sameMethods(method, methodSignature);
    }

    public PsiClass getPsiClassByFilePath(String filePath, String qualifiedClass) {
        return psiSearch.findClassByFilePath(project, filePath, qualifiedClass);
    }

    public PsiClass getPsiClassFromClassAndFileNames(String className, String filePath) {
        return psiSearch.findClass(project, className, filePath);
    }

    public static PsiMethod getPsiMethod(PsiClass psiClass, MethodSignatureObject methodSignatureObject) {
        return PsiSearchService.findMethod(psiClass, methodSignatureObject);
    }

    public static PsiParameter getPsiParameter(PsiMethod psiMethod, ParameterObject parameterObject) {
        return PsiSearchService.findParameter(psiMethod, parameterObject);
    }

    public static PsiField getPsiField(PsiClass psiClass, String fieldName) {
        return PsiSearchService.findField(psiClass, fieldName);
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
        // Explicit iterator: removing through the iterator is the only safe
        // in-loop removal. The previous for-each + List.remove threw
        // ConcurrentModificationException on the first removal, which
        // aborted the filtering for the whole file and let refactorings
        // inside conflicting regions through to replay.
        Iterator<RefactoringObject> iterator = refactorings.iterator();
        while (iterator.hasNext()) {
            RefactoringObject refactoring = iterator.next();
            if(refactoring instanceof InlineMethodObject || refactoring instanceof ExtractMethodObject) {
                continue;
            }

            if (!refactoring.getOriginalFilePath().equals(path)) {
                continue;
            }

            // No per-iteration sync: the caller (RePatch.doMerge) runs
            // vfs.synchronize(project) right after the cherry-pick, before this
            // loop, and nothing here touches files on disk. The previous
            // runWhenSmartWithFuture call was the RefMerge idiom that, on the
            // EDT-dispatched headless path, only queued the sync for an
            // unpredictable later time (and deadlocks if anyone .get()s it).

            setBoundaries(refactoring);
            if (!checkReplayRefactoring(refactoring, conflictingRegions)) {
                iterator.remove();
            }
        }
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
        for(Pair<Integer,Integer> conflictingRegion : conflictingRegions) {
            int conflictingStartLine = conflictingRegion.getLeft();
            int conflictingEndLine = conflictingRegion.getRight();
            // If the refactoring is within the conflicting region, do not replay
            if(refStartLine > conflictingStartLine && refEndLine < conflictingEndLine) {
                return false;
            }
            // Otherwise, if the regions overlap, do not replay
            else if(refStartLine < conflictingStartLine && refEndLine < conflictingEndLine) {
                return false;
            }
            else if(refStartLine > conflictingStartLine && refEndLine > conflictingEndLine) {
                return false;
            }
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

