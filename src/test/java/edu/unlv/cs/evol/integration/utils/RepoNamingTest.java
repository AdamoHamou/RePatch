package edu.unlv.cs.evol.integration.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The owner-suffixed checkout naming contract: <RepoName>-<Owner> derived
 * from the clone URL. The bash twin of this derivation lives in
 * scripts/reset-integration-fixtures.sh.
 */
public class RepoNamingTest {

    @Test
    public void derivesOwnerSuffixedDirectoryName() {
        assertEquals("kafka-linkedin",
                RepoNaming.directoryName("https://github.com/linkedin/kafka"));
        assertEquals("RePatch-AdamoHamou",
                RepoNaming.directoryName("https://github.com/AdamoHamou/RePatch"));
    }

    @Test
    public void toleratesGitSuffixAndTrailingSlash() {
        assertEquals("kafka-linkedin",
                RepoNaming.directoryName("https://github.com/linkedin/kafka.git"));
        assertEquals("kafka-linkedin",
                RepoNaming.directoryName("https://github.com/linkedin/kafka/"));
        assertEquals("kafka-linkedin",
                RepoNaming.directoryName(" https://github.com/linkedin/kafka.git "));
    }

    @Test
    public void exposesOwnerAndRepositorySegments() {
        assertEquals("linkedin", RepoNaming.owner("https://github.com/linkedin/kafka"));
        assertEquals("kafka", RepoNaming.repositoryName("https://github.com/linkedin/kafka"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNull() {
        RepoNaming.directoryName(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUrlWithoutOwnerSegment() {
        RepoNaming.directoryName("https://github.com/kafka");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBareName() {
        RepoNaming.directoryName("kafka");
    }
}
