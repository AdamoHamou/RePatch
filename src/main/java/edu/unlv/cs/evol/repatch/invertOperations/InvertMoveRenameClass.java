package edu.unlv.cs.evol.repatch.invertOperations;

import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.RefactoringOperation;
import edu.unlv.cs.evol.repatch.refactoringObjects.MoveRenameClassObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.refactoringObjects.typeObjects.ClassObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPackage;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.MoveDestination;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassToInnerProcessor;
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor;
import com.intellij.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination;
import com.intellij.refactoring.move.moveInner.MoveInnerProcessor;
import com.intellij.usageView.UsageInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inverts a rename/move class refactoring by performing the opposite
 * rename/move. First operation family migrated onto the Week 4A contract.
 *
 * Operation family: move/rename (class).
 *  - Expected PSI inputs: the destination class (qualified name + file
 *    path from RefactoringMiner), resolvable after the right-side
 *    checkout; for moves, the original package or enclosing class.
 *  - Side effects: class renamed and/or moved back to its original
 *    package/enclosure; imports and references updated by the platform
 *    processors; the containing virtual file is refreshed.
 *  - Failure behavior: missing PSI targets classify as
 *    PRECONDITION_FAILED before anything mutates; an unreportable
 *    common package (RefMiner truncation) is UNSUPPORTED_SHAPE; processor
 *    blowups are PROCESSOR_THREW with the original refactoring intact on
 *    disk only if the processor itself rolled back (IntelliJ processors
 *    are not transactional — a partial rename is possible and the result
 *    object records it).
 *  - Postcondition: the class is resolvable under its original qualified
 *    name from the original file path.
 */
public class InvertMoveRenameClass implements RefactoringOperation {

    private final MoveRenameClassObject moveRenameClassObject;

    // Resolved during prepare (steps 1-2), consumed by execute (step 3).
    private String destQualifiedClass;
    private String srcClassName;
    private String filePath;
    private PsiClass psiClass;
    private VirtualFile vFile;
    private PsiDirectory moveInnerTargetDirectory;
    private PsiClass moveOuterTargetClass;
    private PsiClass moveInnerToInnerTargetClass;
    private PsiDirectory topLevelMoveDirectory;
    private PsiDirectory topLevelMoveParentDirectory;
    private String topLevelMoveRelativePackage;

    public InvertMoveRenameClass(RefactoringObject refactoringObject) {
        this.moveRenameClassObject = (MoveRenameClassObject) refactoringObject;
    }

    @Override
    public String describe() {
        return "invert " + moveRenameClassObject.getRefactoringType()
                + " (" + moveRenameClassObject.getRefactoringDetail() + ")";
    }

    @Override
    public void prepare(RefactoringExecutionContext context) throws PreconditionFailed, UnsupportedShape {
        Project project = context.getProject();
        ClassObject classObject = moveRenameClassObject.getDestinationClassObject();
        destQualifiedClass = classObject.getClassName();
        srcClassName = moveRenameClassObject.getOriginalClassObject().getClassName();
        srcClassName = srcClassName.substring(srcClassName.lastIndexOf(".") + 1);
        filePath = moveRenameClassObject.getDestinationFilePath();

        context.getProjectRoots().addSourceRoot(project, filePath, destQualifiedClass);

        psiClass = context.getPsiSearch().findClass(project, destQualifiedClass, filePath);
        if (psiClass == null) {
            throw new PreconditionFailed("destination class " + destQualifiedClass
                    + " not resolvable from " + filePath);
        }
        vFile = psiClass.getContainingFile().getVirtualFile();

        if (moveRenameClassObject.isMoveMethod()) {
            prepareMoveTargets(context, project);
        }
    }

    /*
     * Resolve the move-branch targets up front so a missing target is a
     * classified precondition failure before anything mutates.
     */
    private void prepareMoveTargets(RefactoringExecutionContext context, Project project)
            throws PreconditionFailed, UnsupportedShape {
        // If the move class refactoring is outer to inner
        if (moveRenameClassObject.isMoveInner()) {
            String originalPackage = moveRenameClassObject.getOriginalClassObject().getPackageName();
            PsiPackage psiPackage = context.getPsiSearch().findPackage(project, originalPackage);
            if (psiPackage == null) {
                throw new PreconditionFailed("original package " + originalPackage + " not found");
            }
            PsiDirectory[] psiDirectories = psiPackage.getDirectories();
            moveInnerTargetDirectory = psiDirectories[0];
            if (psiDirectories.length > 1) {
                String path = filePath.substring(0, filePath.lastIndexOf("/"));
                for (PsiDirectory directory : psiDirectories) {
                    String dirPath = directory.getVirtualFile().getPath();
                    if (dirPath.contains(path)) {
                        moveInnerTargetDirectory = directory;
                        break;
                    }
                }
            }
        }
        // If the move class refactoring is inner to outer
        else if (moveRenameClassObject.isMoveOuter()) {
            String originalTopClass = moveRenameClassObject.getOriginalClassObject().getPackageName();
            String originalFilePath = moveRenameClassObject.getOriginalFilePath();
            moveOuterTargetClass = context.getPsiSearch()
                    .findClassByFilePath(project, originalFilePath, originalTopClass);
            if (moveOuterTargetClass == null) {
                throw new PreconditionFailed("enclosing class " + originalTopClass
                        + " not resolvable from " + originalFilePath);
            }
        }
        // Move the inner class to another class
        else if (moveRenameClassObject.isMoveInnerToInner()) {
            String originalFilePath = moveRenameClassObject.getOriginalFilePath();
            String originalPackage = moveRenameClassObject.getOriginalClassObject().getPackageName();
            moveInnerToInnerTargetClass = context.getPsiSearch()
                    .findClassByFilePath(project, originalFilePath, originalPackage);
            if (moveInnerToInnerTargetClass == null) {
                throw new PreconditionFailed("target enclosing class " + originalPackage
                        + " not resolvable from " + originalFilePath);
            }
        }
        // Otherwise the move class moves a top level class from one package to another
        else {
            String originalPackage = moveRenameClassObject.getOriginalClassObject().getPackageName();
            PsiPackage psiPackage = context.getPsiSearch().findPackage(project, originalPackage);
            if (psiPackage != null) {
                String originalFilePath = moveRenameClassObject.getOriginalFilePath();
                if (originalFilePath.contains("/")) {
                    originalFilePath = originalFilePath.substring(0, originalFilePath.lastIndexOf("/"));
                }
                for (PsiDirectory directory : psiPackage.getDirectories()) {
                    String path = directory.getVirtualFile().getPath();
                    if (path.contains(originalFilePath)) {
                        topLevelMoveDirectory = directory;
                        break;
                    }
                }
            }
            if (topLevelMoveDirectory == null) {
                // The target directory does not exist yet; plan its creation
                // from the common package prefix.
                PsiDirectory currentDirectory = psiClass.getContainingFile().getContainingDirectory();
                String commonPackage = getCommonPackage(currentDirectory, originalPackage);
                // If RefMiner does not report the full package
                if (commonPackage == null) {
                    throw new UnsupportedShape("no common package between "
                            + currentDirectory.getPresentation().getLocationString()
                            + " and " + originalPackage);
                }
                topLevelMoveParentDirectory = getParentDirectory(currentDirectory, commonPackage);
                topLevelMoveRelativePackage = originalPackage.substring(commonPackage.length() + 1);
            }
        }
    }

    @Override
    public void execute(RefactoringExecutionContext context) {
        Project project = context.getProject();

        if (moveRenameClassObject.isRenameMethod()) {
            RefactoringFactory factory = JavaRefactoringFactory.getInstance(project);
            RenameRefactoring renameRefactoring = factory.createRename(psiClass, srcClassName, false, false);
            UsageInfo[] refactoringUsages = renameRefactoring.findUsages();
            List<UsageInfo> usageInfosToRefactor = new ArrayList<>();
            for (UsageInfo usageInfo : refactoringUsages) {
                if (!usageInfo.isNonCodeUsage && usageInfo.isWritable() && !usageInfo.isDynamicUsage() && usageInfo.isValid()) {
                    usageInfosToRefactor.add(usageInfo);
                }
            }
            refactoringUsages = usageInfosToRefactor.toArray(UsageInfo[]::new);
            renameRefactoring.doRefactoring(usageInfosToRefactor.toArray(refactoringUsages));

            context.getIndexing().drainDumbTasks(project);
        }
        if (moveRenameClassObject.isMoveMethod()) {
            if (moveRenameClassObject.isMoveInner()) {
                MoveInnerProcessor processor = new MoveInnerProcessor(project, null);
                processor.setup(psiClass, srcClassName, true,
                        null, true, false, moveInnerTargetDirectory);
                context.getPlatform().invokeAndWait(processor);
            } else if (moveRenameClassObject.isMoveOuter()) {
                PsiClass[] psiClasses = new PsiClass[1];
                psiClasses[0] = psiClass;
                MoveClassToInnerProcessor processor = new MoveClassToInnerProcessor(project, psiClasses,
                        moveOuterTargetClass, true, false, null);
                context.getPlatform().invokeAndWait(processor);
            } else if (moveRenameClassObject.isMoveInnerToInner()) {
                MoveInnerProcessor processor = new MoveInnerProcessor(project, null);
                processor.setup(psiClass, srcClassName, false,
                        null, true, false, moveInnerToInnerTargetClass);
                context.getPlatform().invokeAndWait(processor);
            } else {
                PsiDirectory targetDirectory = topLevelMoveDirectory;
                if (targetDirectory == null) {
                    // Create the planned subdirectory chain for the original package.
                    targetDirectory = createSubdirectory(context, topLevelMoveParentDirectory,
                            topLevelMoveRelativePackage);
                }
                MoveDestination moveDestination = new SingleSourceRootMoveDestination(PackageWrapper
                        .create(Objects.requireNonNull(JavaDirectoryService.getInstance().getPackage(targetDirectory))),
                        targetDirectory);
                PsiElement[] psiElements = new PsiElement[1];
                psiElements[0] = psiClass;
                MoveClassesOrPackagesProcessor moveClassProcessor = new MoveClassesOrPackagesProcessor(project,
                        psiElements, moveDestination, true, false, null);
                context.getPlatform().invokeAndWait(moveClassProcessor);
            }
        }

        context.getPlatform().closeActiveUsageView(project);
        // Update the virtual file of the class
        vFile.refresh(false, true);
    }

    @Override
    public String verifyPostcondition(RefactoringExecutionContext context) {
        if (!moveRenameClassObject.isRenameMethod() && !moveRenameClassObject.isMoveMethod()) {
            return null;
        }
        String originalQualifiedClass = moveRenameClassObject.getOriginalClassObject().getClassName();
        String originalFilePath = moveRenameClassObject.getOriginalFilePath();
        PsiClass inverted = context.getPsiSearch()
                .findClass(context.getProject(), originalQualifiedClass, originalFilePath);
        if (inverted == null) {
            return "class not resolvable under original name " + originalQualifiedClass
                    + " from " + originalFilePath;
        }
        return null;
    }

    /*
     * Get the common package of the directory of the refactored class and the package of the original class
     */
    private String getCommonPackage(PsiDirectory psiDirectory, String originalPackage) {
        String directoryPackage = psiDirectory.getPresentation().getLocationString();
        String[] packages = new String[2];
        packages[0] = originalPackage;
        packages[1] = directoryPackage;
        String commonPackage = StringUtils.getCommonPrefix(packages);
        if (commonPackage.length() == 0) {
            return null;
        }
        if (commonPackage.contains(".")) {
            commonPackage = commonPackage.substring(0, commonPackage.lastIndexOf("."));
        }
        return commonPackage;
    }

    /*
     * Get the PSI directory of the common package
     */
    private PsiDirectory getParentDirectory(PsiDirectory psiDirectory, String commonPackage) {
        if (psiDirectory.getPresentation().getLocationString().equals(commonPackage)) {
            return psiDirectory;
        }
        return getParentDirectory(psiDirectory.getParentDirectory(), commonPackage);
    }

    /*
     * Create the specified subdirectory
     */
    private PsiDirectory createSubdirectory(RefactoringExecutionContext context, PsiDirectory psiDirectory,
                                            String relativePath) {
        if (!relativePath.contains(".")) {
            PsiDirectory candidate = psiDirectory.findSubdirectory(relativePath);
            if (candidate != null) {
                return candidate;
            } else {
                AtomicReference<PsiDirectory> subDirectory = new AtomicReference<>();
                context.getPlatform().runWriteCommand(context.getProject(), () -> {
                    subDirectory.set(psiDirectory.createSubdirectory(relativePath));
                });
                return subDirectory.get();
            }
        }
        String[] directories = relativePath.split("\\.");
        String newSubDirectory = directories[0];
        PsiDirectory candidate = psiDirectory.findSubdirectory(newSubDirectory);
        if (candidate != null) {
            return createSubdirectory(context, candidate, relativePath.substring(newSubDirectory.length() + 1));
        } else {
            AtomicReference<PsiDirectory> subDirectory = new AtomicReference<>();
            context.getPlatform().runWriteCommand(context.getProject(), () -> {
                subDirectory.set(psiDirectory.createSubdirectory(newSubDirectory));
            });
            return createSubdirectory(context, subDirectory.get(), relativePath.substring(newSubDirectory.length() + 1));
        }
    }
}
