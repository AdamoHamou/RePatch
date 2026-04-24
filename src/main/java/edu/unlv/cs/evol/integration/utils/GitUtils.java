package edu.unlv.cs.evol.integration.utils;

import edu.unlv.cs.evol.repatch.utils.Utils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;

public class GitUtils {


    private final Project project;
    private final GitRepository repo;

    public GitUtils(GitRepository repository, Project proj) {
        repo = repository;
        project = proj;
    }

    /*
     * Perform the git checkout with the IntelliJ API.
     */
    public void checkout(String commit) {
        GitThread thread = new GitThread(repo, commit);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // Refresh the virtual file system after the commit
        Utils.refreshVFS();
    }


    public void add() {
        GitAdd thread = new GitAdd(project, repo);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public void reset() {
        Utils.runSystemCommand("git", "clean");
        Git.getInstance().reset(repo, GitResetMode.HARD, "HEAD");
    }

//    public boolean cherrypick(String commitToCherryPick, String newBranchName) throws VcsException {
//        AtomicReference<GitCommandResult> gitCommandResult = new AtomicReference<>();
//
//        Thread thread = new Thread(() -> {
//            try {
//                // Step 1: Create a new branch from current HEAD
//                GitLineHandler createBranchHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.CHECKOUT);
//                createBranchHandler.addParameters("-b", newBranchName); // git checkout -b <newBranchName>
//                GitCommandResult branchResult = Git.getInstance().runCommand(createBranchHandler);
//                if (!branchResult.success()) {
//                    gitCommandResult.set(branchResult);
//                    return;
//                }
//
//                // Step 2: Cherry-pick the commit into the new branch
//                /*
//                // NOTE: IntelliJ may reformat leading whitespaces during code modifications, e.g., replacing tabs with spaces while moving an operation.
//                //   Detection of 'existing file indent' (File > Settings > Editor > Code Style)  requires existing content, e.g., for moving a method, it requires another method in the target class (an indented comment is not enough).
//                //   Also see settings File > Settings > Editor > Code Style > Java to change from spaces to tabs (with smart tabs, i.e., tries to detect spaces).
//                // WORKAROUND: Ignore changing of spaces/tabs during the diff of a  merge:
//                //   Xignore-space-change: This tells Git to treat spaces and tabs as equivalent, ignoring changes in their quantity.
//                //   Xignore-all-space: This tells Git to completely ignore all whitespace when comparing lines.
//                 */
//                GitLineHandler cherryPickHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.CHERRY_PICK);
//                cherryPickHandler.addParameters("--no-commit", "-Xignore-space-change", commitToCherryPick);
//                GitCommandResult cherryPickResult = Git.getInstance().runCommand(cherryPickHandler);
//                gitCommandResult.set(cherryPickResult);
//
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        });
//
//        thread.start();
//        try {
//            thread.join();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//
//        GitCommandResult result = gitCommandResult.get();
//        String output = result != null ? result.getOutputOrThrow() : "";
//
//        if (output.contains("conflict") || (result != null && result.getErrorOutputAsJoinedString().contains("conflict"))) {
//            return hasJavaConflict(output);
//        }
//
//        return false;
//    }

    /**
     * Cherry-picks a commit from a remote repository into a new branch of the current repository.
     *
     * <p>This method is useful for integrating changes across repositories with unrelated histories,
     * such as cherry-picking a merge commit from a GitHub pull request in an upstream project.
     * If the commit is not present locally, it attempts to fetch it from the specified remote.
     * It then creates a new branch from the current HEAD and performs the cherry-pick with:
     * <ul>
     *     <li>{@code --no-commit} to leave the commit staged but uncommitted</li>
     *     <li>{@code --allow-unrelated-histories} to allow cherry-picking across repos</li>
     *     <li>{@code -Xignore-space-change} to ignore whitespace differences</li>
     * </ul>
     *
     * @param remoteName          the name of the remote repository (e.g., "ka") where the commit exists
     * @param commitToCherryPick  the SHA of the commit to cherry-pick
     * @return {@code true} if there was a Java-relevant conflict during cherry-pick (as determined by {@code hasJavaConflict}),
     *         {@code false} otherwise
     */
    public boolean cherrypick(String remoteName, String commitToCherryPick) {
        AtomicReference<GitCommandResult> gitCommandResult = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            try {
                // Step 1: Fetch the commit from remote
                GitLineHandler fetchHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.FETCH);
                fetchHandler.addParameters(remoteName, commitToCherryPick);
                GitCommandResult fetchResult = Git.getInstance().runCommand(fetchHandler);
                if (!fetchResult.success()) {
                    gitCommandResult.set(fetchResult);
                    return;
                }

                // Step 2: Check if it's a merge commit
                GitLineHandler revListHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.REV_LIST);
                revListHandler.addParameters("--parents", "-n", "1", commitToCherryPick);
                GitCommandResult revListResult = Git.getInstance().runCommand(revListHandler);

                boolean isMergeCommit = false;
                if (revListResult.success()) {
                    String[] tokens = revListResult.getOutputAsJoinedString().trim().split("\\s+");
                    isMergeCommit = tokens.length > 2; // Commit hash + >1 parent = merge
                }

                // Step 3: Prepare cherry-pick
                GitLineHandler cherryPickHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.CHERRY_PICK);
                cherryPickHandler.addParameters("--no-commit");

                if (isMergeCommit) {
                    // Default to using the first parent
                    cherryPickHandler.addParameters("-m", "1");
                    System.out.println("Merge commit detected; applying cherry-pick with -m 1");
                }

                cherryPickHandler.addParameters(commitToCherryPick);
                GitCommandResult cherryPickResult = Git.getInstance().runCommand(cherryPickHandler);
                gitCommandResult.set(cherryPickResult);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        GitCommandResult result = gitCommandResult.get();
        String output = result != null ? result.getOutputAsJoinedString() : "";
        String error = result != null ? result.getErrorOutputAsJoinedString() : "";

        System.out.println("Cherry-pick success: " + (result != null && result.success()));
        System.out.println("Cherry-pick error: " + error);

        if (output.contains("conflict") || output.contains("CONFLICT")) {
            System.out.println("Cherry-pick resulted in conflicts.");
            return hasJavaConflict(output);
        }
        return false;
    }



    public boolean merge(String rightCommit) {
        AtomicReference<GitCommandResult> gitCommandResult = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            GitLineHandler lineHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.MERGE);
            lineHandler.addParameters(rightCommit, "--no-commit");
            gitCommandResult.set(Git.getInstance().runCommand(lineHandler));
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        String gitMergeResult = gitCommandResult.get().toString();
        System.out.println("GitMergeResult: " + gitMergeResult);
        if(gitMergeResult.contains("conflict")) {
            return hasJavaConflict(gitMergeResult);
        }
        return false;
    }

    private boolean hasJavaConflict(String gitResult) {
        for (String line : gitResult.split(",")) {
            if (!line.contains("CONFLICT")) {
                continue;
            }
            if (line.contains(".java")) {
                return true;
            }
        }
        return false;
    }

    /*
     * Get the base commit of the merge scenario.
     */
    public String getBaseCommit(String left, String right) {
        VirtualFile root = repo.getRoot();
        class BaseThread extends Thread {
            private final Project project;
            private final VirtualFile root;
            private final String leftCommit;
            private final String rightCommit;
            private String baseCommit;

            BaseThread(Project project, VirtualFile root, String leftCommit, String rightCommit) {
                this.project = project;
                this.root = root;
                this.leftCommit = leftCommit;
                this.rightCommit = rightCommit;
            }
            @Override
            public void run() {
                GitRevisionNumber num = null;
                try {
                    num = GitHistoryUtils.getMergeBase(project, root, leftCommit, rightCommit);
                } catch (VcsException e) {
                    System.out.println("Project: " + project + " LeftCommit: " + leftCommit + " RightCommit: " + rightCommit);
                    e.printStackTrace();
                }
                if(num == null) {
                    this.baseCommit = null;
                }
                else {
                    this.baseCommit = num.getShortRev();
                }
            }

            public String getBaseCommit() {
                return baseCommit;
            }
        }

        BaseThread thread = new BaseThread(project, root, left, right);
        thread.start();
        try {
            thread.join();
            return thread.getBaseCommit();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }

    }


    public static String diff(String dir, String path1, String path2) {
        StringBuilder builder = new StringBuilder();
        try {
            String commands = "git diff --ignore-cr-at-eol --ignore-all-space --ignore-blank-lines --ignore-space-change " +
                    "--no-index -U0 " + path1 + " " + path2;
            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(commands, null, new File(dir));
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            String s;
            while ((s = stdInput.readLine()) != null) {
                builder.append(s);
                builder.append("\n");
            }
            while ((s = stdError.readLine()) != null) {
                builder.append(s);
                builder.append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return builder.toString();

    }


    /*
     * Get the base commit for the IntelliMerge replications. Use this instead of GitUtils because there is no project.
     */
    public static String getBaseCommit(RevCommit leftParent, RevCommit rightParent, Repository repository) throws IOException {
        RevWalk walk = new RevWalk(repository);
        walk.setRevFilter(RevFilter.MERGE_BASE);
        walk.markStart(leftParent);
        walk.markStart(rightParent);
        RevCommit mergeBase = walk.next();
        if(mergeBase == null) {
            return null;
        }
        return mergeBase.getName();
    }

    /*
     * Reset for IntelliMerge replication
     */
    public static void gitReset(org.eclipse.jgit.api.Git git) throws GitAPIException {
        ResetCommand reset = git.reset();
        reset.setRef("HEAD");
        reset.setMode(ResetCommand.ResetType.HARD);
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        String lockPath = git.getRepository().getWorkTree().getAbsolutePath() + ".git/index.lock";
        File f = new File(lockPath);
        if (f.exists()) {
            Utils.runSystemCommand("rm", lockPath);
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    /*
     * Get all merge scenarios for merge scenario collection
     */
    public static Iterable<RevCommit> getMergeScenarios(org.eclipse.jgit.api.Git git) {
        try {
        gitReset(git);
        return git.log().all().setRevFilter(new RevFilter() {
            @Override
            public boolean include(RevWalk revWalk, RevCommit revCommit) throws StopWalkException {
                return revCommit.getParentCount() == 2;
            }

            @Override
            public RevFilter clone() {
                return this;
            }
        }).call();
        } catch (IOException | GitAPIException e) {
            e.printStackTrace();
        }
        return null;
    }



}

class GitAdd extends Thread {
    final GitRepository repo;
    final Project project;

    public GitAdd(Project project, GitRepository repo) {
        this.project = project;
        this.repo = repo;
    }

    @Override
    public void run() {
        GitLineHandler lineHandler = new GitLineHandler(project, repo.getRoot(), GitCommand.ADD);
        lineHandler.addParameters("-A");
        Git.getInstance().runCommand(lineHandler);
    }

}

class GitThread extends Thread {
    final GitRepository repo;
    final String commit;

    public GitThread(GitRepository repo, String commit) {
        this.repo = repo;
        this.commit = commit;
    }

    @Override
    public void run()
    {
        Git.getInstance().reset(repo, GitResetMode.HARD, "HEAD");
        Git.getInstance().checkout(repo, commit, null, true, false, false);
    }
}
