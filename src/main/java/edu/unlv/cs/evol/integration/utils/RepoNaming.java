package edu.unlv.cs.evol.integration.utils;

/**
 * Derives the on-disk checkout directory name for an evaluation repository
 * from its clone URL. The name is owner-prefixed — {@code <Owner>-<RepoName>}
 * — so checkouts of forks with the same repository name cannot collide or be
 * confused with each other (e.g. an apache/kafka clone accidentally used where
 * the linkedin/kafka fork was required):
 *
 * <ul>
 *   <li>{@code https://github.com/linkedin/kafka} → {@code linkedin-kafka}</li>
 *   <li>{@code https://github.com/AdamoHamou/RePatch} → {@code AdamoHamou-RePatch}</li>
 * </ul>
 *
 * The same derivation is implemented in bash by
 * {@code scripts/reset-integration-fixtures.sh}; keep the two in sync.
 */
public final class RepoNaming {

    private RepoNaming() {
    }

    /**
     * @param cloneUrl an HTTPS clone URL whose last two path segments are
     *                 {@code <owner>/<repo>}; a trailing slash or {@code .git}
     *                 suffix is tolerated
     * @return the checkout directory name, {@code <Owner>-<RepoName>}
     * @throws IllegalArgumentException if owner and repository cannot both be
     *                                  derived from the URL
     */
    public static String directoryName(String cloneUrl) {
        return owner(cloneUrl) + "-" + repositoryName(cloneUrl);
    }

    /** @return the repository segment of the clone URL, e.g. {@code kafka} */
    public static String repositoryName(String cloneUrl) {
        return splitOwnerRepo(cloneUrl)[1];
    }

    /** @return the owner segment of the clone URL, e.g. {@code linkedin} */
    public static String owner(String cloneUrl) {
        return splitOwnerRepo(cloneUrl)[0];
    }

    private static String[] splitOwnerRepo(String cloneUrl) {
        if (cloneUrl == null) {
            throw new IllegalArgumentException("clone URL is null");
        }
        String trimmed = cloneUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        String[] segments = trimmed.split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException(
                    "cannot derive <owner>/<repo> from clone URL: " + cloneUrl);
        }
        String owner = segments[segments.length - 2];
        String repo = segments[segments.length - 1];
        // An owner segment containing ':' or '.' means we actually grabbed the
        // scheme or the host — the URL had no <owner>/<repo> path.
        if (owner.isEmpty() || repo.isEmpty() || owner.contains(":") || owner.contains(".")) {
            throw new IllegalArgumentException(
                    "cannot derive <owner>/<repo> from clone URL: " + cloneUrl);
        }
        return new String[]{owner, repo};
    }
}
