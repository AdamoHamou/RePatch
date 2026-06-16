package edu.unlv.cs.evol.integration;

import edu.unlv.cs.evol.integration.data.ConflictingFileData;
import edu.unlv.cs.evol.integration.utils.GitHubUtils;
import edu.unlv.cs.evol.repatch.RePatch;
import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import edu.unlv.cs.evol.integration.utils.EvaluationUtils;
import edu.unlv.cs.evol.integration.utils.GitUtils;
import edu.unlv.cs.evol.integration.utils.RepoNaming;
import edu.unlv.cs.evol.integration.utils.Utils;
import edu.unlv.cs.evol.repatch.utils.FailureEventSink;
import edu.unlv.cs.evol.repatch.utils.LoggingService;
import edu.unlv.cs.evol.repatch.platform.IntelliJ2024PlatformFacade;
import edu.unlv.cs.evol.repatch.platform.PlatformFacade;
import edu.unlv.cs.evol.repatch.platform.VfsSyncService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
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
    private final PlatformFacade platform;
    private final VfsSyncService vfs;
    private final PipelineRunResult runResult = new PipelineRunResult();
    // Which bundled dataset directory under resources/ to read the project and
    // patch lists from. Set per-run in runComparison; "sample_data" is the
    // default 5-PR kafka set, "complete_data" is the full dataset.
    private String dataDir = "sample_data";

    public RePatchIntegration() {
        this(new IntelliJ2024PlatformFacade());
    }

    public RePatchIntegration(PlatformFacade platform) {
        this.project = null;
        this.platform = platform;
        this.vfs = new VfsSyncService(platform);
    }

    /*
     * Use the given git repository to evaluate IntelliMerge, RePatch, and Git.
     * Use the give git repositories (mainline and variant fork) to integrate patches with RePatch and Git
     */
    public void runComparison(String path, String evaluationProject, String dataSet) throws Exception {
        // Startup hygiene: a wedged previous run (same JVM) must not leak its
        // failure events into this run's first scenario.
        FailureEventSink.clear();
        this.dataDir = "complete".equalsIgnoreCase(dataSet) ? "complete_data" : "sample_data";
        System.out.println("[Pipeline] dataset = " + this.dataDir);
        URL url = IntegrationPipeline.class.getResource("/" + dataDir + "/repatch_integration_projects");
        assert url != null;
        InputStream inputStream = url.openStream();
        ArrayList<String> lines = Utils.getLinesFromInputStream(inputStream);
        String projectUrl;
        String projectName;
        GitRepository repo;
        Project proj = null;
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            // mainlineUrl,variantUrl[,branch,pinnedSha] — the variant fork is the
            // project patches are applied to. branch+SHA pin the evaluation state;
            // when omitted (2-column entry, e.g. complete_data), the base defaults
            // to the fork's default-branch HEAD at clone time under a local
            // "repatch-eval" branch. Pinning is preferred for reproducibility,
            // but the 2-column form lets the full dataset run without per-project
            // SHAs (valid for an A/B comparison as long as both sides clone the
            // same upstream state).
            String[] values = line.split(",");
            if (values.length < 2) {
                throw new IllegalStateException("Malformed line in " + dataDir + "/repatch_integration_projects"
                        + " (expected at least mainlineUrl,variantUrl): " + line);
            }
            String mainLineUrl = values[0].trim();
            String[] mainLineUrls = mainLineUrl.split("/"); // Begin to construct the mainline repo name, e.g. kafka
            String mainLineName = mainLineUrls[mainLineUrls.length - 1];
            String variantUrl = values[1].trim();
            String branch = values.length > 2 && !values[2].trim().isEmpty() ? values[2].trim() : "repatch-eval";
            String pinnedSha = values.length > 3 ? values[3].trim() : "";
            projectUrl = variantUrl; // This is the project that we want to apply patches to.. it can be interchanged
            if (!line.contains(evaluationProject)) {
                continue;
            }
            proj = Project.findFirst("fork_url = ?", projectUrl);
            if (proj == null) {
                projectName = openProject(path, projectUrl, mainLineUrl, branch, pinnedSha); // checkout dir name, e.g. linkedin-kafka
                System.out.println("Starting Project -> " + projectName);
                proj = new Project(mainLineUrl, mainLineName, projectUrl, projectName);
                proj.saveIt();
                GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                List<GitRepository> repos = repoManager.getRepositories();
                if (repos.size() == 0) {
                    repo = registerAndGetRepository(repoManager, path, projectName);
                } else {
                    repo = repos.get(0);
                }
            } else if (proj.isDone()) {
                continue;
            } else {
                projectName = openProject(path, projectUrl, mainLineUrl, branch, pinnedSha);
                System.out.println("Continuing " + projectName);
                GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                List<GitRepository> repos = repoManager.getRepositories();
                if (repos.isEmpty()) {
                    repo = registerAndGetRepository(repoManager, path, projectName);
                } else {
                    repo = repos.get(0);
                }
            }
            System.out.println("Repository for Integration -> " + repo);
            evaluateProject(repo, proj, projectUrl);
            proj.setDone();
            proj.saveIt();


        }
        // While Base is still open — the summary includes per-PR verdicts.
        runResult.printSummary();
    }

    /*
     * IntelliJ 2024 dropped the implicit "discover repo from .git folder" behavior of
     * GitRepositoryManager.updateRepository. We now explicitly register the project root
     * as a Git VCS directory mapping, then look up the repo. The mapping APIs require
     * write-intent (EDT) but updateRepository / getRepositoryForFile assert background
     * thread, so we split the work between EDT and a pooled thread. The mapping change
     * is processed asynchronously by GitRepositoryManager, so we poll for the repo to
     * appear with a generous deadline.
     */
    private GitRepository registerAndGetRepository(GitRepositoryManager repoManager, String basePath, String projectName) throws Exception {
        VirtualFile projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + projectName);
        assert projectRoot != null;
        ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
        List<VcsDirectoryMapping> mappings = new ArrayList<>(vcsManager.getDirectoryMappings());
        boolean alreadyMapped = mappings.stream().anyMatch(m -> "Git".equals(m.getVcs()));
        if (!alreadyMapped) {
            mappings.add(new VcsDirectoryMapping(projectRoot.getPath(), "Git"));
            vcsManager.setDirectoryMappings(mappings);
        }
        return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            GitRepository repo = null;
            while (System.currentTimeMillis() < deadline) {
                repoManager.updateRepository(projectRoot);
                repo = repoManager.getRepositoryForFile(projectRoot);
                if (repo != null) break;
                try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            return repo;
        }).get();
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
    private void evaluateProject(GitRepository repo, Project proj, String projectUrl) throws Exception {
        URL url = IntegrationPipeline.class.getResource("/" + dataDir + "/repatch_integration_patches");

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
            // Match patches by the variant fork's URL, not the checkout dir name:
            // the dir is owner-prefixed (linkedin-kafka) and no longer a substring
            // of the URL in the patches file.
            if(values[1].trim().equals(projectUrl)) {
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
                // One failing PR must not abort the remaining PRs (a server
                // run once died on PR 1/5 and left the DB empty). Classify the
                // failure, leave the patch not-done, and continue.
                long scenarioStart = System.currentTimeMillis();
                // Stamp every line emitted while this PR's scenario runs on
                // this thread (doMerge, the invert/replay tree, GitUtils) with
                // a per-integration operation id, so a multi-PR run log stays
                // greppable per PR. Cleared in finally so it never leaks into
                // the next PR.
                LoggingService prLog = LoggingService.forOperation(this.project.getName(), "PR-" + values[2]);
                LoggingService.setOperationContext("PR-" + values[2]);
                try {
                    PipelineRunResult.ScenarioOutcome outcome = evaluateMergeScenario(data, repo, proj, patch);
                    patch.setDone();
                    patch.saveIt();
                    runResult.record(Integer.parseInt(values[2]), outcome,
                            System.currentTimeMillis() - scenarioStart, null);
                } catch (Exception | AssertionError e) {
                    prLog.error("[Pipeline] SCENARIO_FAILED PR " + values[2] + " — "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace();
                    runResult.record(Integer.parseInt(values[2]), PipelineRunResult.ScenarioOutcome.FAILED,
                            System.currentTimeMillis() - scenarioStart,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    // Safety drain: a scenario that threw (or aborted inside
                    // doMerge) never reached the in-scenario drain — persist
                    // its events now so they can't leak into the next PR.
                    persistFailureEvents(proj, patch, null);
                    LoggingService.clearOperationContext();
                }
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
    private PipelineRunResult.ScenarioOutcome evaluateMergeScenario(String[] values, GitRepository repo,
                                       Project proj, Patch patch) throws VcsException {

        GitUtils gitUtils = new GitUtils(repo, project);
        gitUtils.reset();
        String tempPath = System.getProperty("user.home") + "/temp/";
        Utils.clearTemp(tempPath + "manualMerge");
        // Utils.clearTemp(tempPath + "intelliMerge");

        String mergeCommitHash = values[0]; // values[1];
        MergeCommit mergeCommit = MergeCommit.findFirst("commit_hash = ?", mergeCommitHash);
        if(mergeCommit != null && mergeCommit.isDone()) {
            return PipelineRunResult.ScenarioOutcome.ALREADY_DONE;
        }


        String rightParent = values[0];
        String leftParent = values[1];
        //String baseCommit = gitUtils.getBaseCommit(leftParent, rightParent);
        String baseCommit = values[2];
        // Skip cases without a base commit
        if (baseCommit == null) {
            return PipelineRunResult.ScenarioOutcome.NO_BASE_COMMIT;
        }

        gitUtils.checkout(rightParent);
        vfs.commitAndReparse(project);


        gitUtils.checkout(leftParent);
//        boolean isConflicting = gitUtils.merge(rightParent);
        boolean isConflicting = gitUtils.cherrypick(remoteRepoName, mergeCommitHash);
        System.out.println("-> Is conflicting: " + isConflicting);
        if(!isConflicting) {
            // This should always be conflicting
            // Now we are using Git CherryPick
            //System.out.println("-> Error merging with Git CherryPick");
            return PipelineRunResult.ScenarioOutcome.NON_CONFLICTING;
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
            return PipelineRunResult.ScenarioOutcome.ALREADY_DONE;
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

        // RePatch has finished for this scenario — every classified failure it
        // recorded is in the sink. Persist them next to the verdict rows, fully
        // attributed (the safety drain in evaluateProject only catches scenarios
        // that died before reaching this point and has no merge_commit id).
        persistFailureEvents(proj, patch, mergeCommit);

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
        return PipelineRunResult.ScenarioOutcome.EVALUATED;
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
     * Clone the given project into the given directory and pin the checkout:
     * create <branch> at <pinnedSha> so a fresh clone starts from exactly the
     * state the reset script enforces on existing checkouts. We detach onto the
     * SHA before (re)creating the branch so this also works when <branch> is
     * the clone's default branch. A failed clone removes the partial directory
     * so the next run can retry instead of mistaking it for a valid checkout.
     */
    private void cloneProject(File cloneDir, String url, String branch, String pinnedSha) {
        System.out.println("TASK: cloning project -> " + url + " into " + cloneDir);
        try (Git git = Git.cloneRepository().setURI(url).setDirectory(cloneDir).call()) {
            if (pinnedSha != null && !pinnedSha.isEmpty()) {
                git.checkout().setName(pinnedSha).call();
                git.branchCreate().setName(branch).setStartPoint(pinnedSha).setForce(true).call();
                git.checkout().setName(branch).call();
                System.out.println("TASK: pinned " + branch + " at " + pinnedSha);
            } else {
                // No pinned SHA (2-column dataset entry): stay on the fork's
                // default-branch HEAD and label a local branch there. The base
                // is the clone-time HEAD, not a fixed commit — reproducibility
                // across runs depends on the upstream fork not advancing.
                String head = git.getRepository().resolve("HEAD").getName();
                git.branchCreate().setName(branch).setForce(true).call();
                git.checkout().setName(branch).call();
                System.out.println("TASK: unpinned — using default HEAD " + head + " as base for " + branch);
            }
        }
        catch(Exception e) {
            deleteRecursively(cloneDir);
            throw new IllegalStateException("Failed to clone " + url + " into " + cloneDir
                    + " (partial clone removed) — " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(File dir) {
        if (!dir.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            System.err.println("-> Failed to remove partial clone at " + dir + ": " + e.getMessage());
        }
    }

    /*
     * Open the evaluation project, cloning it first when the checkout is
     * missing. The checkout directory is owner-prefixed — <Owner>-<RepoName>,
     * e.g. linkedin-kafka — so forks can't be confused with their mainline
     * (see RepoNaming). Returns the directory name.
     */
    private String openProject(String path, String url, String remoteOriginUrl, String branch, String pinnedSha) {
        String dirName = RepoNaming.directoryName(url);

        // get the remote repo name - the repo we are cherry-picking from.
        String remoteProjectName = remoteOriginUrl.substring(remoteOriginUrl.lastIndexOf("/") + 1);
        remoteRepoName = remoteProjectName;
        System.out.println("-> Remote Repo Name: " + remoteProjectName);
        File pathToProject = new File(path, dirName);

        if(!pathToProject.exists()) {
            cloneProject(pathToProject, url, branch, pinnedSha);
            // add mainLineUrl to the repo we are working with as a second remote
            addRemote(pathToProject, remoteProjectName, remoteOriginUrl);
        } else if (!new File(pathToProject, ".git").isDirectory()) {
            throw new IllegalStateException(pathToProject + " exists but is not a git checkout —"
                    + " delete it and re-run so the pipeline can clone " + url);
        }

        try {
            this.project = platform.openProject(pathToProject.toPath());
        }
        catch(Exception e) {
            throw new IllegalStateException("Failed to open project at " + pathToProject
                    + " — " + e.getMessage(), e);
        }
        return dirName;

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
     * Drain the FailureEventSink and persist one failure_event row per entry,
     * stamped with the scenario's ids and operation id. This is the pipeline's
     * ONLY failure-event writer, and it runs on the EDT — the thread the
     * ActiveJDBC Base connection is bound to; producers never touch the DB.
     * Observation only: a failed write is logged, never thrown, so it cannot
     * change a scenario's outcome or verdicts.
     */
    private void persistFailureEvents(Project proj, Patch patch, MergeCommit mergeCommit) {
        List<FailureEventSink.Entry> events = FailureEventSink.drain();
        for (FailureEventSink.Entry event : events) {
            try {
                new FailureEvent(event.phase, event.refactoringType, event.category, event.evidence,
                        FailureEventSink.isPipelineArtifact(event.category, event.evidence),
                        LoggingService.currentOperationId(),
                        proj == null ? null : proj.getId(),
                        patch == null ? null : patch.getId(),
                        mergeCommit == null ? null : mergeCommit.getId()).saveIt();
                runResult.recordFailure(event.category);
            } catch (RuntimeException e) {
                LoggingService.forProject(project == null ? null : project.getName())
                        .error("[Pipeline] failure_event write failed (" + event.category + "): "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
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
