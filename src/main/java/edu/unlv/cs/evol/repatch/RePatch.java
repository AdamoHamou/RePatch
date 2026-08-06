package edu.unlv.cs.evol.repatch;

import edu.unlv.cs.evol.repatch.invertOperations.InvertRefactorings;
import edu.unlv.cs.evol.repatch.matrix.Matrix;
import edu.unlv.cs.evol.repatch.replayOperations.ReplayRefactorings;
import edu.unlv.cs.evol.repatch.utils.RefactoringObjectUtils;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.eclipse.jgit.api.Git;
import edu.unlv.cs.evol.repatch.utils.GitUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;


public class RePatch extends AnAction {

    Git git;
    Project project;



    @Override
    public void update(@NotNull AnActionEvent e) {
        // Using the event, evaluate the context, and enable or disable the action.
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
        List<GitRepository> repos = repoManager.getRepositories();
        GitRepository repo = repos.get(0);

        String leftCommit = System.getenv("LEFT_COMMIT");
        String rightCommit = System.getenv("RIGHT_COMMIT");
        String baseCommit = System.getenv("BASE_COMMIT");

        List<Refactoring> detectedRefactorings = new ArrayList<>();
        refMerge(rightCommit, leftCommit, baseCommit, project, repo, detectedRefactorings, new HashSet<>());

    }

    /*
     * Gets the directory of the project that's being merged, then it calls the function that performs the merge.
     */
    public ArrayList<Pair<RefactoringObject, RefactoringObject>> refMerge(String rightCommit, String leftCommit, String baseCommit,
                                                                          Project project, GitRepository repo,
                                                                          List<Refactoring> detectedRefactorings,
                                                                          Set<String> conflictingFiles) {
        this.project = project;
        File dir = new File(Objects.requireNonNull(project.getBasePath()));
        try {
            git = Git.open(dir);
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

        return doMerge(rightCommit, leftCommit, baseCommit, repo, detectedRefactorings, conflictingFiles);

    }

    /*
     * This method gets the refactorings that are between the base commit and the left and right commits. It uses the
     * matrix to determine if any of the refactorings are conflicting or have ordering dependencies.
     * Then it checks out the base commit, saving it in a temporary directory. It checks out the right commit, undoes
     * the refactorings, and saves the content into a respective temporary directory. It does the same thing for the
     * left commit, but it uses the current directory instead of saving it to a new one. After it's undone all the
     * refactorings, the merge function is called and it replays the refactorings.
     *
     * We modify this to take in custom baseCommit (parent of the remote commit you want to cherry-pick
     *  in order to mimic cherry pick
     */
    private ArrayList<Pair<RefactoringObject, RefactoringObject>> doMerge(String rightCommit, String leftCommit, String baseCommit,
                                                                          GitRepository repo,
                                                                          List<Refactoring> detectedRefactorings,
                                                                          Set<String> conflictingFiles){
        long time = System.currentTimeMillis();
        GitUtils gitUtils = new GitUtils(repo, project);
        //String baseCommit = gitUtils.getBaseCommit(leftCommit, rightCommit); // we pass this directly in the method
        System.out.println("Detecting refactorings");
        // Which sides get scoped to the conflicting files: both | left | right | off.
        // Toggle exists so the effect of scoping each side can be measured
        // independently without a code change.
        String scopeMode = System.getProperty("repatch.scopeDetection", "both");
        Set<String> rightScope = ("both".equals(scopeMode) || "right".equals(scopeMode)) ? conflictingFiles : null;
        Set<String> leftScope = ("both".equals(scopeMode) || "left".equals(scopeMode)) ? conflictingFiles : null;
        // Detection-phase and overall wall-clock budgets, in minutes. Defaults
        // preserve historical behavior (11-minute RefactoringMiner get, 15-minute
        // overall budget from the start of doMerge).
        long refMinerTimeoutMinutes = Long.getLong("repatch.refMinerTimeoutMinutes", 11L);
        long overallBudgetMillis = Long.getLong("repatch.timeoutMinutes", 15L) * 60_000L;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<ArrayList<RefactoringObject>> rightRefsAtomic = new AtomicReference<>(new ArrayList<>());
        AtomicReference<ArrayList<RefactoringObject>> leftRefsAtomic = new AtomicReference<>(new ArrayList<>());
        Future futureRefMiner = executor.submit(() -> {
            rightRefsAtomic.set(detectAndSimplifyRefactorings(rightCommit, baseCommit, detectedRefactorings, rightScope));
            leftRefsAtomic.set(detectAndSimplifyRefactorings(leftCommit, baseCommit, detectedRefactorings, leftScope));
        });
        try {
            futureRefMiner.get(refMinerTimeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            // Without the cancel, the detection thread keeps mining inside a
            // dead scenario, holding CPU and JGit handles into the next one.
            futureRefMiner.cancel(true);
            System.out.println("RePatch Timed Out");
            return null;
        }
        catch (InterruptedException | ExecutionException e) {
            futureRefMiner.cancel(true);
            System.out.println("There was an error detecting refactorings");
            e.printStackTrace();
            return null;
        } finally {
            executor.shutdownNow();
        }

        ArrayList<RefactoringObject> rightRefs = rightRefsAtomic.get();
        ArrayList<RefactoringObject> leftRefs = leftRefsAtomic.get();

        long time2 = System.currentTimeMillis();
        // If it timed out
        if(budgetExceeded(time, time2, overallBudgetMillis)) {
            System.out.println("RePatch Timed Out");
            return null;
        }


        gitUtils.checkout(rightCommit);
        // Update the PSI classes after the commit
        Utils.reparsePsiFiles(project);
        Utils.dumbServiceHandler(project);
        // Optional strict mode: a failed inversion leaves the tree half-inverted,
        // and the failed refactoring stays in the list so replay later re-applies
        // a refactoring whose inverse never happened. Abort cleanly (-1 verdict)
        // instead of proceeding on the mismatched tree. Off by default: inversion
        // failures are common in validated runs (0-30 per patch) whose verdicts
        // still matched git, so aborting on any failure would discard usable runs.
        boolean abortOnInvertFailure = Boolean.getBoolean("repatch.abortOnInvertFailure");
        System.out.println("Inverting right refactorings");
        int failedRefactorings = InvertRefactorings.invertRefactorings(rightRefs, project);
        if(abortOnInvertFailure && failedRefactorings > 0) {
            Utils.log(project.getName(), failedRefactorings
                    + " right-side inversion failures; aborting patch cleanly for " + rightCommit);
            // checkout() cleans and hard-resets first, dropping the half-inverted
            // tree BEFORE any commit is created from it.
            gitUtils.checkout(leftCommit);
            return null;
        }
        // Inversion edits outside the patch's own file set can only be
        // over-reach (comment/text-occurrence rewrites, PSI resolution
        // spill-over); they would ride the undo commit into the cherry-pick
        // as spurious conflicts, so restore them before committing.
        int outOfFootprint = gitUtils.restoreFilesOutsideFootprint(baseCommit, rightCommit);
        if(outOfFootprint > 0) {
            Utils.log(project.getName(), "-> Discarded " + outOfFootprint
                    + " out-of-footprint inversion edits for " + rightCommit);
        }
        Utils.reparsePsiFiles(project);
        Utils.dumbServiceHandler(project);
        String rightUndoCommit = gitUtils.addAndCommit();
        if(!rightRefs.isEmpty() && rightUndoCommit != null
                && gitUtils.sameTree(rightUndoCommit, rightCommit)) {
            // The rename processors resolved nothing and edited nothing:
            // every "inverted" refactoring was a no-op, so the cherry-pick
            // degenerates to a plain cherry-pick of the patch. Counted as
            // success by the per-operation handlers, hence this explicit
            // marker for run forensics.
            Utils.log(project.getName(), "-> Vacuous inversion: undo tree identical to right commit "
                    + rightCommit + " despite " + rightRefs.size() + " refactorings to invert");
        }
        gitUtils.checkout(leftCommit);
        // Update the PSI classes after the commit
        Utils.reparsePsiFiles(project);
        Utils.dumbServiceHandler(project);
        System.out.println("Inverting left refactorings");
        int failedLeftRefactorings = InvertRefactorings.invertRefactorings(leftRefs, project);
        failedRefactorings += failedLeftRefactorings;
        if(abortOnInvertFailure && failedLeftRefactorings > 0) {
            Utils.log(project.getName(), failedLeftRefactorings
                    + " left-side inversion failures; aborting patch cleanly for " + leftCommit);
            gitUtils.checkout(leftCommit);
            return null;
        }

        gitUtils.addAndCommit();

        String message = failedRefactorings + " refactorings were not inverted for " + leftCommit + " and " + rightCommit;
        Utils.log(project.getName(), message);

        // boolean isConflicting = gitUtils.merge(rightUndoCommit);
        // Cherry-picking the undo commit directly applies only the inversion
        // diff (its parent is the right commit) — the patch's own content
        // never reaches the target. Re-parent the undo tree onto the
        // cherry-pick base so the applied diff is base -> (right minus
        // inverted refactorings); fail open to the old behavior if the
        // re-parent fails.
        String rebasedUndo = gitUtils.reparentOntoBase(rightUndoCommit, baseCommit);
        boolean isConflicting = gitUtils.cherryPick(rebasedUndo != null ? rebasedUndo : rightUndoCommit);

        Utils.refreshVFS();
        Utils.reparsePsiFiles(project);
        Utils.dumbServiceHandler(project);

        // Check if any of the refactorings are conflicting or have ordering dependencies
        System.out.println("Detecting refactoring conflicts");
        Matrix matrix = new Matrix(project);

        Pair<ArrayList<Pair<RefactoringObject, RefactoringObject>>, ArrayList<RefactoringObject>> pair = matrix.detectConflicts(leftRefs, rightRefs);

        time2 = System.currentTimeMillis();
        // Timeout if it's been 15 minutes
        if(budgetExceeded(time, time2, overallBudgetMillis)) {
            System.out.println("RePatch Timed Out");
            return null;
        }

        ArrayList<RefactoringObject> refactorings = pair.getRight();
        if(isConflicting) {
            List<String> conflictingFilePaths = gitUtils.getConflictingFilePaths();
            for(String conflictingFilePath : conflictingFilePaths) {
                Utils utils = new Utils(project);
                String absoluteConflictingFilePath = project.getBasePath() + "/" + conflictingFilePath;
                try {
                    utils.removeRefactoringsInConflictingFile(conflictingFilePath, absoluteConflictingFilePath, refactorings);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Combine the lists so we can perform all the refactorings on the merged project
        // Replay all of the refactorings
        System.out.println("Replaying refactorings");
        ReplayRefactorings.replayRefactorings(pair.getRight(), project);

        return pair.getLeft();

    }

    /*
     * Use RefMiner to detect refactorings in commits between the base commit and the parent commit. Compare each newly
     * detected refactoring against previously detected refactorings to check for transitivity or if the refactorings can
     * be simplified.
     */
    public ArrayList<RefactoringObject> detectAndSimplifyRefactorings(String commit, String base, List<Refactoring> detectedRefactorings,
                                                                      Set<String> conflictingFiles) {
        ArrayList<RefactoringObject> simplifiedRefactorings = new ArrayList<>();
        Matrix matrix = new Matrix(project);
        GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();
        try {
            miner.detectBetweenCommits(git.getRepository(), base, commit,
                new RefactoringHandler() {
                    @Override
                    public void handle(String commitId, List<Refactoring> refactorings) {
                        // Add each refactoring to refResult
                        for(Refactoring refactoring : refactorings) {
                            // Create the refactoring object so we can compare and update
                            detectedRefactorings.add(refactoring);
                            RefactoringObject refactoringObject = RefactoringObjectUtils.createRefactoringObject(refactoring);
                            // If the refactoring type is not presently supported, skip it
                            if(refactoringObject == null) {
                                continue;
                            }
                            // simplify refactorings and check if factoring is transitive
                            matrix.simplifyAndInsertRefactorings(refactoringObject, simplifiedRefactorings);
                        }
                    }
                });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return filterToConflictingFiles(commit, simplifiedRefactorings, conflictingFiles);
    }

    /*
     * Keep only the refactorings that touch a file git failed to auto-merge.
     * Runs after matrix simplification so transitivity still sees the full
     * detected set; only the invert/replay list is narrowed. A null or empty
     * set means scoping is unavailable — fail open and keep everything.
     * Refactorings without a file path (e.g. Rename Package) are kept: they
     * are inherently cross-file.
     */
    // Static + package-private for DetectionScopeTest: pure set logic.
    static ArrayList<RefactoringObject> filterToConflictingFiles(String commit, ArrayList<RefactoringObject> refactorings,
                                                                 Set<String> conflictingFiles) {
        if (conflictingFiles == null || conflictingFiles.isEmpty()) {
            return refactorings;
        }
        ArrayList<RefactoringObject> scoped = new ArrayList<>();
        for (RefactoringObject refactoring : refactorings) {
            if (refactoring.getOriginalFilePath() == null && refactoring.getDestinationFilePath() == null) {
                scoped.add(refactoring);
            } else if (conflictingFiles.contains(refactoring.getOriginalFilePath())
                    || conflictingFiles.contains(refactoring.getDestinationFilePath())) {
                scoped.add(refactoring);
            }
        }
        System.out.println("-> Scoped detection for " + commit + " to conflicting files: kept "
                + scoped.size() + "/" + refactorings.size());
        return scoped;
    }

    /*
     * SPEC-7: the wall-clock budget test, extracted so the arithmetic is unit
     * testable. The historical bug was reversed operands (start - now), which
     * is always negative and made both mid-pipeline timeout checks dead code.
     */
    static boolean budgetExceeded(long startMillis, long nowMillis, long budgetMillis) {
        return nowMillis - startMillis > budgetMillis;
    }

}