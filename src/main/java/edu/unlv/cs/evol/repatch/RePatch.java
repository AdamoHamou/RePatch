package edu.unlv.cs.evol.repatch;

import edu.unlv.cs.evol.repatch.invertOperations.InvertRefactorings;
import edu.unlv.cs.evol.repatch.matrix.Matrix;
import edu.unlv.cs.evol.repatch.platform.IntelliJ2024PlatformFacade;
import edu.unlv.cs.evol.repatch.platform.PlatformFacade;
import edu.unlv.cs.evol.repatch.platform.RefactoringExecutionContext;
import edu.unlv.cs.evol.repatch.platform.VfsSyncService;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;


public class RePatch extends AnAction {

    Git git;
    Project project;
    private final PlatformFacade platform;
    private final VfsSyncService vfs;

    public RePatch() {
        this(new IntelliJ2024PlatformFacade());
    }

    public RePatch(PlatformFacade platform) {
        this.platform = platform;
        this.vfs = new VfsSyncService(platform);
    }



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
        refMerge(rightCommit, leftCommit, baseCommit, project, repo, detectedRefactorings);

    }

    /*
     * Gets the directory of the project that's being merged, then it calls the function that performs the merge.
     */
    public ArrayList<Pair<RefactoringObject, RefactoringObject>> refMerge(String rightCommit, String leftCommit, String baseCommit,
                                                                          Project project, GitRepository repo,
                                                                          List<Refactoring> detectedRefactorings) {
        this.project = project;
        File dir = new File(Objects.requireNonNull(project.getBasePath()));
        try {
            git = Git.open(dir);
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

        return doMerge(rightCommit, leftCommit, baseCommit, repo, detectedRefactorings);

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
                                                                          List<Refactoring> detectedRefactorings){
        long time = System.currentTimeMillis();
        GitUtils gitUtils = new GitUtils(repo, project);
        //String baseCommit = gitUtils.getBaseCommit(leftCommit, rightCommit); // we pass this directly in the method
        System.out.println("Detecting refactorings");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<ArrayList<RefactoringObject>> rightRefsAtomic = new AtomicReference<>(new ArrayList<>());
        AtomicReference<ArrayList<RefactoringObject>> leftRefsAtomic = new AtomicReference<>(new ArrayList<>());
        // When rightCommit is a GitHub PR merge commit, the linear range
        // base..rightCommit covers only the merge commit itself (1 commit,
        // 0 refactorings) — the PR's actual changes live on the merge's
        // second parent (the branched-in tip). Detect against that instead
        // so RefactoringMiner walks the PR's real commit range. For non-
        // merge commits the helper returns the input unchanged.
        String rightDetectCommit = resolveMergedInTip(rightCommit);
        Future futureRefMiner = executor.submit(() -> {
            rightRefsAtomic.set(detectAndSimplifyRefactorings(rightDetectCommit, baseCommit, detectedRefactorings));
            leftRefsAtomic.set(detectAndSimplifyRefactorings(leftCommit, baseCommit, detectedRefactorings));
        });
        try {
            futureRefMiner.get(11, TimeUnit.MINUTES);


        } catch (TimeoutException e) {
            System.out.println("RePatch Timed Out");
            return null;
        }
        catch (InterruptedException | ExecutionException e) {
            System.out.println("There was an error detecting refactorings");
            e.printStackTrace();
            return null;
        }

        ArrayList<RefactoringObject> rightRefs = rightRefsAtomic.get();
        ArrayList<RefactoringObject> leftRefs = leftRefsAtomic.get();

        long time2 = System.currentTimeMillis();
        // If it timed out
        if((time - time2) > 900000) {
            System.out.println("RePatch Timed Out");
            return null;
        }


        gitUtils.checkout(rightCommit);
        // Update the PSI classes after the commit
        vfs.commitAndReparse(project);
        RefactoringExecutionContext executionContext = new RefactoringExecutionContext(project, platform);
        System.out.println("Inverting right refactorings");
        int failedRefactorings = InvertRefactorings.invertRefactorings(rightRefs, executionContext);
        vfs.commitAndReparse(project);
        String rightUndoCommit = gitUtils.addAndCommit();
        // No commit hash means the inverted right side could not be committed
        // (e.g. missing git identity on a fresh machine). Abort this scenario
        // with a classified log line instead of cherry-picking null — the
        // caller treats a null return as a failed scenario and moves on.
        if (rightUndoCommit == null) {
            Utils.log(project.getName(), "[GitPipeline] SCENARIO_ABORTED — committing the inverted right side"
                    + " produced no commit for " + leftCommit + " and " + rightCommit
                    + "; check git identity (git config user.name / user.email)");
            return null;
        }
        gitUtils.checkout(leftCommit);
        // Update the PSI classes after the commit
        vfs.commitAndReparse(project);
        System.out.println("Inverting left refactorings");
        failedRefactorings += InvertRefactorings.invertRefactorings(leftRefs, executionContext);

        gitUtils.addAndCommit();

        String message = failedRefactorings + " refactorings were not inverted for " + leftCommit + " and " + rightCommit;
        Utils.log(project.getName(), message);

        // boolean isConflicting = gitUtils.merge(rightUndoCommit);
        boolean isConflicting = gitUtils.cherryPick(rightUndoCommit);

        vfs.synchronize(project);

        // Check if any of the refactorings are conflicting or have ordering dependencies
        System.out.println("Detecting refactoring conflicts");
        Matrix matrix = new Matrix(project);

        Pair<ArrayList<Pair<RefactoringObject, RefactoringObject>>, ArrayList<RefactoringObject>> pair = matrix.detectConflicts(leftRefs, rightRefs);

        time2 = System.currentTimeMillis();
        // Timeout if it's been 15 minutes
        if((time - time2) > 900000) {
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
        int failedReplays = ReplayRefactorings.replayRefactorings(pair.getRight(), executionContext);
        if (failedReplays > 0) {
            Utils.log(project.getName(), failedReplays + " refactorings were not replayed for "
                    + leftCommit + " and " + rightCommit);
        }

        return pair.getLeft();

    }

    /*
     * Use RefMiner to detect refactorings in commits between the base commit and the parent commit. Compare each newly
     * detected refactoring against previously detected refactorings to check for transitivity or if the refactorings can
     * be simplified.
     */
    public ArrayList<RefactoringObject> detectAndSimplifyRefactorings(String commit, String base, List<Refactoring> detectedRefactorings) {
        ArrayList<RefactoringObject> simplifiedRefactorings = new ArrayList<>();
        Matrix matrix = new Matrix(project);
        GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();
        // Counters surface RefactoringMiner exceptions that the default no-op
        // handleException would otherwise swallow. The 2024.x migration
        // records ~50% fewer refactorings than the 2020.1.2 baseline; if a
        // per-commit miner failure is the cause, it lands here.
        int[] commitsHandled = {0};
        int[] commitsFailed = {0};
        try {
            miner.detectBetweenCommits(git.getRepository(), base, commit,
                new RefactoringHandler() {
                    @Override
                    public void handle(String commitId, List<Refactoring> refactorings) {
                        commitsHandled[0]++;
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

                    @Override
                    public void handleException(String commitId, Exception e) {
                        commitsFailed[0]++;
                        System.out.println("[RefactoringMiner] handleException on commit "
                                + commitId + " (base=" + base + " -> " + commit + "): "
                                + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                });
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("[RefactoringMiner] detect summary base=" + base + " -> " + commit
                + ": commitsHandled=" + commitsHandled[0]
                + " commitsFailed=" + commitsFailed[0]
                + " refactoringsAccumulated=" + detectedRefactorings.size());
        return simplifiedRefactorings;
    }

    /**
     * If {@code commitSha} resolves to a merge commit (>=2 parents), return
     * the SHA of its second parent — the tip of the branched-in line, which
     * for a GitHub PR merge is the PR's last contributed commit. For non-
     * merge commits, return {@code commitSha} unchanged.
     *
     * RefactoringMiner's detectBetweenCommits(base, end) iterates the linear
     * commit range base..end. When end is a merge commit and base is its
     * first parent, that range contains only the merge commit itself and
     * RefactoringMiner reports zero refactorings — the merged-in commits
     * are reachable only via the second parent. Rewriting end to the second
     * parent makes the walk traverse the PR's real changes.
     */
    private String resolveMergedInTip(String commitSha) {
        if (git == null) {
            return commitSha;
        }
        try {
            org.eclipse.jgit.lib.ObjectId id = git.getRepository().resolve(commitSha);
            if (id == null) {
                return commitSha;
            }
            try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                org.eclipse.jgit.revwalk.RevCommit c = rw.parseCommit(id);
                if (c.getParentCount() >= 2) {
                    String resolved = c.getParent(1).getName();
                    System.out.println("[RefactoringMiner] right-side detect target rewritten: "
                            + commitSha + " (merge) -> " + resolved + " (second parent)");
                    return resolved;
                }
            }
        } catch (Exception e) {
            System.out.println("[RefactoringMiner] resolveMergedInTip failed for " + commitSha
                    + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
        return commitSha;
    }

}