package edu.unlv.cs.evol.integration.utils;

import org.kohsuke.github.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public class GitHubUtils {
    private static final String GITHUB_URL = "https://github.com/";
    private static final String BITBUCKET_URL = "https://bitbucket.org/";
    private static final Logger logger = Logger.getLogger(GitHubUtils.class.getName());
    private GitHub gitHub;

    public GitHubUtils() {

    }

    /**
     *
     * @param cloneURL
     * @return
     */
    private static String extractRepositoryName(String cloneURL) {
        int hostLength = 0;
        if (cloneURL.startsWith(GITHUB_URL)) {
            hostLength = GITHUB_URL.length();
        } else if (cloneURL.startsWith(BITBUCKET_URL)) {
            hostLength = BITBUCKET_URL.length();
        }
        int indexOfDotGit = cloneURL.length();
        if (cloneURL.endsWith(".git")) {
            indexOfDotGit = cloneURL.indexOf(".git");
        } else if (cloneURL.endsWith("/")) {
            indexOfDotGit = cloneURL.length() - 1;
        }
        return cloneURL.substring(hostLength, indexOfDotGit);
    }

//    /**
//     * establish connection to GitHub using OAuthToken
//     * @return instance of GitHub connection
//     */
//    private GitHub connectToGitHub() {
//        if (gitHub == null) {
//            try {
//                Properties prop = new Properties();
//                InputStream input = new FileInputStream("github-oauth.properties");
//                prop.load(input);
//                String oAuthToken = prop.getProperty("OAuthToken");
//                if (oAuthToken != null) {
//                    gitHub = GitHub.connectUsingOAuth(oAuthToken);
//                    if (gitHub.isCredentialValid()) {
//                        logger.info("Connected to GitHub with OAuth token");
//                    }
//                } else {
//                    gitHub = GitHub.connect();
//                }
//            } catch (FileNotFoundException e) {
//                logger.warning("File github-oauth.properties was not found in RefactoringMiner's execution directory " + e);
//            } catch (IOException ioe) {
//                ioe.printStackTrace();
//            }
//        }
//        return gitHub;
//    }
    /**
     * Connects to GitHub using a token from `github-oauth.properties`,
     * or anonymously if not configured.
     *
     * @return an authenticated or anonymous GitHub client
     */
    public GitHub connectToGitHub() {
        if (gitHub != null) return gitHub;

        try (InputStream input = getClass().getClassLoader().getResourceAsStream("github-oauth.properties")) {
            Properties prop = new Properties();
            if (input != null) {
                prop.load(input);
                String oAuthToken = prop.getProperty("OAuthToken");
                if (oAuthToken != null && !oAuthToken.isBlank()) {
                    gitHub = GitHub.connectUsingOAuth(oAuthToken);
                    if (gitHub.isCredentialValid()) {
                        logInfo("Connected to GitHub with OAuth token.");
                    } else {
                        logWarn("GitHub credentials are invalid.");
                    }
                }
            }

            if (gitHub == null) {
                gitHub = GitHub.connectAnonymously();
                logInfo("Connected to GitHub anonymously.");
            }

        } catch (IOException e) {
            logWarn("Error connecting to GitHub: " + e.getMessage());
        }

        return gitHub;
    }

    private void logInfo(String msg) {
        System.out.println("[INFO] " + msg);
    }

    private void logWarn(String msg) {
        System.err.println("[WARN] " + msg);
    }

    /**
     * extracts the all pull requests in a given repository for a specific status
     * @param cloneURL The GitHub repository
     * @param status pull request status, OPEN, CLOSED, etc
     * @return list of pull requests of a specific status (OPEN, CLOSED)
     * @throws IOException
     * @see GHPullRequest
     */
    public List<GHPullRequest> getPullRequestsInRepo(String cloneURL, GHIssueState status) throws IOException {

        GitHub gitHub = connectToGitHub();

        String repoName = extractRepositoryName(cloneURL);
        GHRepository repository = gitHub.getRepository(repoName);

        return repository.getPullRequests(status);
    }

    /**
     * extracts the merge commit sha of a given pull request
     * @param cloneURL the url of the repository to be cloned
     * @param pr pull request number
     * @return commit sha of a pull request
     * @throws IOException
     */
    public GHPullRequest getMergeCommitSha(String cloneURL, int pr) throws IOException {
        GitHub gitHub = connectToGitHub();

        String repoName = extractRepositoryName(cloneURL);
        GHRepository repository = gitHub.getRepository(repoName);
        return repository.getPullRequest(pr);
    }


    /**
     * extracts the merge commit sha of list of pull request
     * @param cloneURL the url of the repository to be cloned
     * @param pr pull request number
     * @return ArrayList of merge commit sha
     * @throws IOException
     */
    public ArrayList<String> getMergeCommitShaList(String cloneURL, int...pr) throws IOException {
        ArrayList<String> mergeCommitSha = new ArrayList<>();
        String repoName = extractRepositoryName(cloneURL);
        GHRepository repository = gitHub.getRepository(repoName);
        Arrays.stream(pr).forEach(
                item -> {
                    try {
                        mergeCommitSha.add(repository.getPullRequest(item).getMergeCommitSha());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
        );

        return mergeCommitSha;
    }

    /**
     * Retrieves the merge commit SHA of a pull request, if it has been merged.
     *
     * @param repoOwner   the owner of the GitHub repository (e.g. "octocat")
     * @param repoName    the name of the GitHub repository (e.g. "Hello-World")
     * @param prNumber    the pull request number
     * @param oauthToken  a GitHub personal access token (can be null for public repos with no rate limit)
     * @return the merge commit SHA if the PR is merged, or {@code null} otherwise
     * @throws IOException if there is a network or API error
     */
    public static String getMergeCommitOfPR(String repoOwner, String repoName, int prNumber, String oauthToken) throws IOException {
        GitHub github = (oauthToken != null && !oauthToken.isEmpty())
                ? new GitHubBuilder().withOAuthToken(oauthToken).build()
                : GitHub.connectAnonymously();

        GHRepository repository = github.getRepository(repoOwner + "/" + repoName);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);

        if (pullRequest.isMerged()) {
            return pullRequest.getMergeCommitSha();
        } else {
            System.out.println("PR #" + prNumber + " is not merged.");
            return null;
        }
    }
}
