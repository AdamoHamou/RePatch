package edu.unlv.cs.evol.integration;

import edu.unlv.cs.evol.integration.data.ConflictingFileData;
import edu.unlv.cs.evol.integration.utils.GitHubUtils;
import edu.unlv.cs.evol.repatch.RePatch;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.integration.utils.EvaluationUtils;
import edu.unlv.cs.evol.integration.utils.GitUtils;
import edu.unlv.cs.evol.integration.utils.Utils;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsFullCommitDetails;
import edu.unlv.cs.evol.integration.data.ConflictBlockData;
import edu.unlv.cs.evol.integration.database.*;
import git4idea.GitCommit;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GHPullRequest;
import org.refactoringminer.api.Refactoring;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class RePatchIntegration {
    private com.intellij.openapi.project.Project project;
    private String remoteRepoName;

    public RePatchIntegration() {
        this.project = null;
    }

    /*
     * Use the given git repository to evaluate IntelliMerge, RePatch, and Git.
     * Use the give git repositories (mainline and variant fork) to integrate patches with RePatch and Git
     */
    public void runComparison(String path, String evaluationProject) throws Exception {
        URL url = IntegrationPipeline.class.getResource("/sample_data/repatch_integration_projects");
        assert url != null;
        InputStream inputStream = url.openStream();
        ArrayList<String> lines = Utils.getLinesFromInputStream(inputStream);
        String projectUrl;
        String projectName;
        GitRepository repo;
        Project proj = null;
        for (String line : lines) {
            // split mainline repo and variant fork repo
            String[] values = line.split(",");
            String mainLineUrl = values[0];
            String[] mainLineUrls = mainLineUrl.split("/"); // Begin to construct the mainline repo name, e.g. kafka
            String mainLineName = mainLineUrls[mainLineUrls.length - 1];
            String variantUrl = values[1];
            projectUrl = variantUrl; // This is the project that we want to apply patches to.. it can be interchanged
            if (!line.contains(evaluationProject)) {
                continue;
            }
            proj = Project.findFirst("fork_url = ?", projectUrl);
            if (proj == null) {
                projectName = openProject(path, projectUrl, mainLineUrl).substring(1); //name of the fork repo, e.g linkedin
                System.out.println("Starting Project -> " + projectName);
                proj = new Project(mainLineUrl, mainLineName, projectUrl, projectName);
                proj.saveIt();
                GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                List<GitRepository> repos = repoManager.getRepositories();
                if (repos.size() == 0) {
                    VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(path + "/" + projectName + "/.git");
                    GitRepositoryManager.getInstance(project).updateRepository(virtualFile);
                    assert virtualFile != null;
                    repo = repoManager.getRepositoryForFile(virtualFile);
                } else {
                    repo = repos.get(0);
                }
            } else if (proj.isDone()) {
                continue;
            } else {
                projectName = openProject(path, projectUrl, mainLineUrl).substring(1);
                System.out.println("Continuing " + projectName);
                GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                List<GitRepository> repos = repoManager.getRepositories();
                repo = repos.get(0);
            }
            System.out.println("Repository for Integration -> " + repo);
            evaluateProject(repo, proj, projectName);
            proj.setDone();
            proj.saveIt();


        }
    }

    /*
     * Evaluate merge scenarios with refactoring-involved conflicts in the given project.
     */
//    private void evaluateProject(GitRepository repo, Project proj, String projectName) throws IOException {
//        URL url = IntegrationPipeline.class.getResource("/refMerge_evaluation_commits");
//        InputStream inputStream = url.openStream();
//        ArrayList<String> lines = Utils.getLinesFromInputStream(inputStream);
//        int i = 0;
//        for(String line : lines) {
//            String[] values = line.split(";");
//            if(values[0].contains(projectName)) {
//                System.out.println("Evaluating on " + ++i + ": " + values[1]);
//                evaluateMergeScenario(values, repo, proj);
//            }
//        }
//
//    }
    private void evaluateProject(GitRepository repo, Project proj, String projectName) throws Exception {
        URL url = IntegrationPipeline.class.getResource("/sample_data/repatch_integration_patches");

        InputStream inputStream = url.openStream();
        ArrayList<String> lines = Utils.getLinesFromInputStream(inputStream);

        // get the commit at the git HEAD of the repo (variant fork)
        VcsFullCommitDetails commit =  getHeadCommit(repo);
        if (commit != null) {
            System.out.println("Git HEAD Commit Hash: " + commit.getId().asString());
        }

        int i = 0;
        for(String line : lines) {
            String[] values = line.split(",");
//            System.out.println("VALUES: " + Arrays.toString(values));
            if(values[1].contains(projectName)) {
                System.out.println(">>>>>>>>>Patch Integration " + ++i + ": PR " + values[2]+ "<<<<<<<<<<");
                // add PR to patch table
                Patch patch = new Patch(Integer.valueOf(values[2]),String.valueOf(values[3]),0, proj);
                patch.saveIt();
                // Get the merge commit of the PR
                // values[0] = Github url of the mainline
                // values[2] = merged PR number

                GHPullRequest mergedPullRequest = new GitHubUtils().getMergeCommitSha(values[0], Integer.valueOf(values[2]));
                String prMergeCommit = mergedPullRequest.getMergeCommitSha();
                String prMergeAuthor = mergedPullRequest.getMergedBy().getName();
                String prMergeAuthorEmail = mergedPullRequest.getMergedBy().getEmail();
                long prTimeStamp = mergedPullRequest.getMergedAt().getTime();

                // get the parent of the merge commit
                VcsFullCommitDetails mergeParents = getCommitDetails(repo, prMergeCommit);
                List<Hash> parents = mergeParents.getParents();
                String mergeParentSha = null;
                if(!parents.isEmpty()) {
                    mergeParentSha = parents.get(0).asString();
                    System.out.println("-> Parent SHA (Base/Left): " + mergeParentSha);
                }

                System.out.println(" -> MergeCommitSha: " + prMergeCommit);

                // fail here if merge parent commit is null <--- This shouldn't happen
                assert mergeParentSha != null;

                // Now we construct the left, right and base parent commits
                // since we are using cherry pick, base commit will the parent of the remote commit you want to cherry-pick
                String gitHeadCommit =  commit.getId().asString();


                String rightCommit = prMergeCommit;
                String leftCommit = gitHeadCommit;
                String baseCommit  = mergeParentSha;

                String[] data = {rightCommit, leftCommit, baseCommit, prMergeAuthor, prMergeAuthorEmail, String.valueOf(prTimeStamp)};

//                evaluateMergeScenario(values, repo, proj);
                evaluateMergeScenario(data, repo, proj, patch);
                patch.setDone();
                patch.saveIt();
            }
        }

    }

    /**
     * Retrieves the latest commit (i.e., the commit at HEAD) from the specified Git repository.
     *
     * <p>This method uses to fetch the Git log
     * for the HEAD reference of the given repository. It returns the most recent commit if available.
     * If no commits are found or an exception occurs, {@code null} is returned.</p>
     *
     * @param repo the {@link GitRepository} from which to retrieve the HEAD commit.
     * @return the {@link VcsFullCommitDetails} representing the HEAD commit, or {@code null} if not found or on error.
     */
    public VcsFullCommitDetails getHeadCommit(GitRepository repo) {
        try {
            // Use GitHistoryUtils to get log for HEAD with only 1 entry
            @NotNull List<GitCommit> commits = GitHistoryUtils.history(this.project, repo.getRoot(), "HEAD");
            if (!commits.isEmpty()) {
                return commits.get(0); // The latest commit at HEAD
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Retrieves detailed information about a specific Git commit within the given repository.
     *
     * <p>This method uses {@link GitHistoryUtils#(Project, VirtualFile, String)} to look up the commit
     * based on the provided SHA hash. It returns the first matching {@link VcsFullCommitDetails} if found.
     *
     * @param repo      the {@link GitRepository} where the commit is located
     * @param commitSha the SHA-1 hash of the commit to retrieve
     * @return a {@link VcsFullCommitDetails} object containing detailed metadata about the commit
     * @throws Exception if no commit with the given SHA is found
     */
    public VcsFullCommitDetails getCommitDetails(GitRepository repo, String commitSha) throws Exception {
        @NotNull List<GitCommit> commits = GitHistoryUtils.history(this.project, repo.getRoot(), commitSha);
        if (!commits.isEmpty()) {
            return commits.get(0);
        } else {
            throw new Exception("-> Commit not found: " + commitSha);
        }
    }


    /*
     * Run RePatch, IntelliMerge, and Git on the given merge scenario.
     * Run RePatch and Git on the given merge scenario
     */
    private void evaluateMergeScenario(String[] values, GitRepository repo,
                                       Project proj, Patch patch) throws VcsException {

        GitUtils gitUtils = new GitUtils(repo, project);
        gitUtils.reset();
        String tempPath = System.getProperty("user.home") + "/temp/";
        Utils.clearTemp(tempPath + "manualMerge");
        // Utils.clearTemp(tempPath + "intelliMerge");

        String mergeCommitHash = values[0]; // values[1];
        MergeCommit mergeCommit = MergeCommit.findFirst("commit_hash = ?", mergeCommitHash);
        if(mergeCommit != null && mergeCommit.isDone()) {
            return;
        }


        String rightParent = values[0];
        String leftParent = values[1];
        //String baseCommit = gitUtils.getBaseCommit(leftParent, rightParent);
        String baseCommit = values[2];
        // Skip cases without a base commit
        if (baseCommit == null) {
            return;
        }

        gitUtils.checkout(rightParent);
        Utils.reparsePsiFiles(project);
        Utils.dumbServiceHandler(project);


        gitUtils.checkout(leftParent);
//        boolean isConflicting = gitUtils.merge(rightParent);
        boolean isConflicting = gitUtils.cherrypick(remoteRepoName, mergeCommitHash);
        System.out.println("-> Is conflicting: " + isConflicting);
        if(!isConflicting) {
            // This should always be conflicting
            // Now we are using Git CherryPick
            //System.out.println("-> Error merging with Git CherryPick");
            return;
        }
        // Set patch's is_conflicting column to true
        patch.setIsConflicting();
        patch.saveIt();

        // Add merge commit to database
        if (mergeCommit == null) {
            mergeCommit = new MergeCommit(mergeCommitHash, isConflicting, leftParent,
                    rightParent, proj, patch, values[3], values[4], Long.parseLong(values[5]));
            mergeCommit.saveIt();
        } else if (mergeCommit.isDone()) {
            return;
        } else if (!mergeCommit.isDone()) {
            mergeCommit.delete();
            mergeCommit = new MergeCommit(mergeCommitHash, isConflicting, leftParent,
                    rightParent, proj, patch, values[3], values[4], Long.parseLong(values[5]));
            mergeCommit.saveIt();
        }
        String resultDir = System.getProperty("user.home") + "/results/" + project.getName() + "/" + "commit" + mergeCommit.getId();

        String refMergePath = resultDir + "/refMerge";
        String gitMergePath = resultDir + "/git";
//        String intelliMergePath = resultDir + "/intelliMerge";


        // Remove unmerged and non-java files from Git and RePatch results to save space
        // Use project path
        EvaluationUtils.removeUnmergedAndNonJavaFiles(project.getBasePath());

        Utils.saveContent(project, gitMergePath);
        gitUtils.reset();


        // Merge the merge scenario with the three tools and record the runtime
        DumbService.getInstance(project).completeJustSubmittedTasks();

        // Run RePatch
        Pair<ArrayList<Pair<RefactoringObject, RefactoringObject>>, Long> refMergeConflictsAndRuntime =
                runRefMerge(project, repo, rightParent, leftParent, baseCommit, mergeCommit);

        EvaluationUtils.removeUnmergedAndNonJavaFiles(project.getBasePath());
        Utils.saveContent(project, refMergePath);
        DumbService.getInstance(project).completeJustSubmittedTasks();


        File refMergeConflictDirectory = new File(resultDir + "/refMergeResults");
        File gitConflictDirectory = new File(resultDir + "/gitResults");
        //File intelliMergeConflictDirectory = new File(resultDir + "/intelliMergeResults");
        refMergeConflictDirectory.mkdirs();
        gitConflictDirectory.mkdirs();
        //intelliMergeConflictDirectory.mkdirs();

        Utils.runSystemCommand("cp", "-r", refMergePath + "/.", refMergeConflictDirectory.getAbsolutePath());
        Utils.runSystemCommand("cp", "-r", gitMergePath + "/.", gitConflictDirectory.getAbsolutePath());
        //Utils.runSystemCommand("cp", "-r", intelliMergePath + "/.", intelliMergeConflictDirectory.getAbsolutePath());



        // Get the conflict blocks from each of the merged results as well as the number of conflict blocks
        List<Pair<ConflictingFileData, List<ConflictBlockData>>> refMergeConflicts = EvaluationUtils
                .extractMergeConflicts(refMergePath, "RePatch", true);
        List<Pair<ConflictingFileData, List<ConflictBlockData>>> gitMergeConflicts = EvaluationUtils
                .extractMergeConflicts(gitMergePath, "Git-CherryPick", true);
//        List<Pair<ConflictingFileData, List<ConflictBlockData>>> intelliMergeConflicts = EvaluationUtils
//                .extractMergeConflicts(intelliMergePath, "IntelliMerge", true);

        List<String> relativePaths = new ArrayList<>();
        for(Pair<ConflictingFileData, List<ConflictBlockData>> gitConflictFiles : gitMergeConflicts) {
            relativePaths.add(gitConflictFiles.getLeft().getFilePath());
        }

        // Compare IntelliMerge and RePatch conflict blocks for discrepancies
        //EvaluationUtils.getSameConflicts(refMergeConflicts, intelliMergeConflicts);

        System.out.println("-> Elapsed RePatch runtime = " + refMergeConflictsAndRuntime);
        //System.out.println("Elapsed IntelliMerge runtime = " + intelliMergeRuntime);

        int totalConflictingLOC = 0;
        int totalConflicts = 0;
        int totalConflictingFiles = 0;
        // If RefMiner or RePatch timeout
        if(refMergeConflictsAndRuntime.getRight() < 0) {
            MergeResult refMergeResult = new MergeResult("RePatch", -1, -1, -1, -1, mergeCommit);
            refMergeResult.saveIt();
        }
        // Add RePatch data to database
        else {
            List<Pair<RefactoringObject, RefactoringObject>> refactoringConflicts = refMergeConflictsAndRuntime.getLeft();
            List<String> files = new ArrayList<>();
            for (Pair<ConflictingFileData, List<ConflictBlockData>> pair : refMergeConflicts) {
                totalConflicts += pair.getRight().size();
                totalConflictingLOC += pair.getLeft().getConflictingLOC();
                if(!files.contains(pair.getLeft().getFilePath())) {
                    files.add((pair.getLeft().getFilePath()));
                    totalConflictingFiles++;
                }

            }
            totalConflicts += refactoringConflicts.size();
            MergeResult refMergeResult = new MergeResult("RePatch", totalConflictingFiles, totalConflicts, totalConflictingLOC,
                    refMergeConflictsAndRuntime.getRight(), mergeCommit);
            refMergeResult.saveIt();
            // Add conflicting files to database;
            for (Pair<ConflictingFileData, List<ConflictBlockData>> pair : refMergeConflicts) {
                ConflictingFile conflictingFile = new ConflictingFile(refMergeResult, pair.getLeft());
                conflictingFile.saveIt();
                // Add each conflict block for the conflicting file
                for (ConflictBlockData conflictBlockData : pair.getRight()) {
                    ConflictBlock conflictBlock = new ConflictBlock(conflictingFile, conflictBlockData);
                    conflictBlock.saveIt();
                }
            }

            // Add refactoring conflict data to database
            for (Pair<RefactoringObject, RefactoringObject> pair : refactoringConflicts) {
                RefactoringConflict refactoringConflict = new RefactoringConflict(pair.getLeft(), pair.getRight(), refMergeResult);
                refactoringConflict.saveIt();
            }
        }

        // Add Git data to database
        totalConflictingLOC = 0;
        totalConflicts = 0;
        totalConflictingFiles = 0;
        List<String> files = new ArrayList<>();
        for(Pair<ConflictingFileData, List<ConflictBlockData>> pair : gitMergeConflicts) {
            totalConflicts += pair.getRight().size();
            totalConflictingLOC += pair.getLeft().getConflictingLOC();
            if(!files.contains(pair.getLeft().getFilePath())) {
                files.add((pair.getLeft().getFilePath()));
                totalConflictingFiles++;
            }
        }
        MergeResult gitMergeResult = new MergeResult("Git-CherryPick", totalConflictingFiles, totalConflicts, totalConflictingLOC, 0, mergeCommit);
        gitMergeResult.saveIt();
        // Add conflicting files to database
        for(Pair<ConflictingFileData, List<ConflictBlockData>> pair : gitMergeConflicts) {
            ConflictingFile conflictingFile = new ConflictingFile(gitMergeResult, pair.getLeft());
            conflictingFile.saveIt();
            // Add each conflict block for the conflicting file
            for(ConflictBlockData conflictBlockData : pair.getRight()) {
                ConflictBlock conflictBlock = new ConflictBlock(conflictingFile, conflictBlockData);
                conflictBlock.saveIt();
            }
        }


        // Add IntelliMerge data to database
//        if(intelliMergeRuntime < 0) {
//            MergeResult intelliMergeResult = new MergeResult("IntelliMerge", -1, -1, -1, -1, mergeCommit);
//            intelliMergeResult.saveIt();
//        }
//        else {
//            totalConflictingLOC = 0;
//            totalConflicts = 0;
//            totalConflictingFiles = 0;
//            files = new ArrayList<>();
//            for (Pair<ConflictingFileData, List<ConflictBlockData>> pair : intelliMergeConflicts) {
//                totalConflicts += pair.getRight().size();
//                totalConflictingLOC += pair.getLeft().getConflictingLOC();
//                if(!files.contains(pair.getLeft().getFilePath())) {
//                    files.add((pair.getLeft().getFilePath()));
//                    totalConflictingFiles++;
//                }
//            }
//            MergeResult intelliMergeResult = new MergeResult("IntelliMerge", totalConflictingFiles, totalConflicts, totalConflictingLOC,
//                    intelliMergeRuntime, mergeCommit);
//            intelliMergeResult.saveIt();
//            // Add conflicting files to database
//            for (Pair<ConflictingFileData, List<ConflictBlockData>> pair : intelliMergeConflicts) {
//                ConflictingFile conflictingFile = new ConflictingFile(intelliMergeResult, pair.getLeft());
//                conflictingFile.saveIt();
//                // Add each conflict block for the conflicting file
//                for (ConflictBlockData conflictBlockData : pair.getRight()) {
//                    ConflictBlock conflictBlock = new ConflictBlock(conflictingFile, conflictBlockData);
//                    conflictBlock.saveIt();
//                }
//            }
//        }

        Utils.clearTemp(gitMergePath);
        //Utils.clearTemp(intelliMergePath);
        Utils.clearTemp(refMergePath);
        // Save space since we can perform a git merge easily to see results
        Utils.clearTemp(gitConflictDirectory.getAbsolutePath());

        // If RePatch and IntelliMerge both timed out, free additional space
        if(refMergeConflictsAndRuntime.getRight() < 0){
            Utils.clearTemp(resultDir);
        }
//        if(intelliMergeRuntime < 0 || refMergeConflictsAndRuntime.getRight() < 0) {
//            Utils.clearTemp(resultDir);
//        }

        mergeCommit.setDone();
        mergeCommit.saveIt();
    }

    /*
     * Merge the left and right parent using RePatch. Return how long it takes for RePatch to finish
     */
    private Pair<ArrayList<Pair<RefactoringObject, RefactoringObject>>, Long> runRefMerge(com.intellij.openapi.project.Project project,
                                                                                          GitRepository repo,
                                                                                          String rightParent,
                                                                                          String leftParent,
                                                                                          String baseParent,
                                                                                          MergeCommit mergeCommit) {
        ArrayList<Pair<RefactoringObject, RefactoringObject>> conflicts = new ArrayList<>();
        List<org.refactoringminer.api.Refactoring> refactorings = new ArrayList<>();
        RePatch refMerging = new RePatch();
        System.out.println("-> Starting RePatch");
        long time = System.currentTimeMillis();
        try {
            conflicts = refMerging.refMerge(rightParent, leftParent, baseParent, project, repo, refactorings);
        }
        catch(AssertionError | OutOfMemoryError | LargeObjectException.OutOfMemory e) {
            if(!refactorings.isEmpty()) {
                recordRefactorings(refactorings, mergeCommit);
            }
            e.printStackTrace();
        }
        long time2 = System.currentTimeMillis();
        // If RePatch times out
        if(conflicts == null || (time2 - time) > 900000) {
            time = -1;
            System.out.println("-> RePatch timed out");
            if(!refactorings.isEmpty()) {
                recordRefactorings(refactorings, mergeCommit);
            }
            return Pair.of(new ArrayList<>(), time);
        }
        System.out.println("RePatch is done");
        recordRefactorings(refactorings, mergeCommit);
        return Pair.of(conflicts, time2 - time);
    }

    /*
     * Merge the left and right parent using IntelliMerge via command line. Return how long it takes for IntelliMerge
     * to finish
     */
//    private long runIntelliMerge(String repoPath, List<String> commits, String output, String mergeCommit) {
//        ExecutorService executor = Executors.newSingleThreadExecutor();
//        final Future future = executor.submit(() -> {
//            IntelliMerge merge = new IntelliMerge();
//            try {
//                merge.mergeBranchesForRefMergeEvaluation(repoPath, commits, output, true);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        });
//        long time2 = System.currentTimeMillis();
//        long time = System.currentTimeMillis();
//        try {
//            System.out.println("Starting IntelliMerge");
//            future.get(15, TimeUnit.MINUTES);
//            time2 = System.currentTimeMillis();
//            System.out.println("IntelliMerge is done");
//            return time2 - time;
//        } catch (InterruptedException | ExecutionException | TimeoutException | OutOfMemoryError e) {
//            e.printStackTrace();
//            return -1;
//        }
//    }


    /*
     * Clone the given project.
     */
    private void cloneProject(String path, String url) {
        System.out.println("TASK: cloning project -> " + url);
        String projectName = url.substring(url.lastIndexOf("/"));
        String clonePath = path + projectName;
        try {
            Git.cloneRepository().setURI(url).setDirectory(new File(clonePath)).call();
        }
        catch(GitAPIException | JGitInternalException e) {
            e.printStackTrace();
        }
    }

    /*
     * Open the given project.
     */
    private String openProject(String path, String url, String remoteOriginUrl) {
        String projectName = url.substring(url.lastIndexOf("/"));

        // get the remote repo name - the repo we are cherry-picking from.
//        String remoteProjectName = remoteOriginUrl.substring(remoteOriginUrl.lastIndexOf("/"));
        String remoteProjectName = remoteOriginUrl.substring(remoteOriginUrl.lastIndexOf("/") + 1);
        remoteRepoName = remoteProjectName;
        System.out.println("-> Remote Repo Name: " + remoteProjectName);
        File pathToProject = new File(path + projectName);

        try {
            if(!pathToProject.exists()) {

                cloneProject(path, url);
                // add mainLineUrl to the repo we are working with as
                addRemote(pathToProject, remoteProjectName, remoteOriginUrl);
            }

            this.project = ProjectUtil.openOrImport(pathToProject.toPath(), null, false);

        }
        catch(Exception e) {
            e.printStackTrace();
        }
        return projectName;

    }

    /**
     * Adds a remote repository to the given local Git repository and fetches its references.
     *
     * <p>If the specified remote already exists, the method will exit without making changes.
     * Otherwise, it will:
     * <ul>
     *   <li>Add the remote under the given name with the specified URL.</li>
     *   <li>Run {@code git fetch <remoteName>} to retrieve branches and other references.</li>
     * </ul>
     *
     * @param localRepoPath the local repository's root directory (must contain a .git folder)
     * @param remoteName    the name to assign to the remote (e.g., "origin", "upstream")
     * @param remoteUrl     the URL of the remote Git repository (e.g., "https://github.com/user/repo.git")
     */
    public void addRemote(File localRepoPath, String remoteName, String remoteUrl) {
        try {
            // Open the local repository
            Git git = Git.open(localRepoPath);

            // Step 1: Check if the remote already exists
            List<RemoteConfig> remotes = git.remoteList().call();
            boolean exists = remotes.stream().anyMatch(remote -> remote.getName().equals(remoteName));

            if (exists) {
                System.out.println("-> Remote already exists: " + remoteName);
                return;
            }

            // Step 2: Add the remote
            RemoteAddCommand remoteAddCommand = git.remoteAdd();
            remoteAddCommand.setName(remoteName);
            remoteAddCommand.setUri(new URIish(remoteUrl));
            remoteAddCommand.call();

            System.out.println("-> Remote added: " + remoteName + " -> " + remoteUrl);

            // Step 3: Fetch from the newly added remote
            System.out.println("-> Fetching from remote: " + remoteName);
            FetchCommand fetchCommand = git.fetch();
            fetchCommand.setRemote(remoteName);
            fetchCommand.call();
            System.out.println("-> Fetch complete <-----------.");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("-> Failed to add or fetch remote: " + remoteName);
        }
    }

    /*
     * Record each refactoring that was detected by RefactoringMiner
     */
    private void recordRefactorings(List<Refactoring> refactorings, MergeCommit mergeCommit) {
        for (Refactoring refactoring : refactorings) {
            String refactoringType = refactoring.getRefactoringType().toString();
            String refactoringDetail = refactoring.toString();
            if (refactoringDetail.length() > 1999) {
                refactoringDetail = refactoringDetail.substring(0, 1999);
            }
            edu.unlv.cs.evol.integration.database.Refactoring refactoringRecord =
                    new edu.unlv.cs.evol.integration.database.Refactoring(refactoringType, refactoringDetail, mergeCommit);
            refactoringRecord.saveIt();
        }
    }
}
