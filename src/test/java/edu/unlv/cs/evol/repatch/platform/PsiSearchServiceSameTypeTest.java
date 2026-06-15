package edu.unlv.cs.evol.repatch.platform;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Type-string matching for {@link PsiSearchService#sameType} — the matcher
 * precision fix behind the 12660 {@code findMethod} misses where the method
 * name was present but the signature was wrongly rejected.
 *
 * The positive cases are real RefMiner {@code UMLType.toString()} (uml) vs PSI
 * {@code getPresentableText()} (psi) strings captured from a kafka pipeline run
 * (the [SigProbe] triage). The negative cases guard against the qualifier-
 * stripping fallback over-matching genuinely different types.
 */
public class PsiSearchServiceSameTypeTest {

    // ---- real captured mismatches that MUST now match ----------------------

    @Test
    public void genericArgumentWhitespaceDiffMatches() {
        // newConsumer 11-arg return type: only difference is the space after the comma.
        assertTrue(PsiSearchService.sameType(
                "KafkaConsumer<String,String>", "KafkaConsumer<String, String>"));
    }

    @Test
    public void outerClassQualifierDiffMatches() {
        // handleWithGroupError return type: uml carries the AdminApiHandler. qualifier
        // and no inner whitespace; psi is the simple name with whitespace.
        assertTrue(PsiSearchService.sameType(
                "AdminApiHandler.ApiResult<CoordinatorKey,Map<MemberIdentity,Errors>>",
                "ApiResult<CoordinatorKey, Map<MemberIdentity, Errors>>"));
    }

    @Test
    public void packageQualifierInsideGenericsMatches() {
        assertTrue(PsiSearchService.sameType(
                "java.util.Map<java.lang.String,java.lang.Integer>", "Map<String, Integer>"));
    }

    @Test
    public void varargsVersusArrayMatches() {
        assertTrue(PsiSearchService.sameType("String[]", "String..."));
        assertTrue(PsiSearchService.sameType("int[]", "int..."));
    }

    @Test
    public void identicalSimpleTypesMatch() {
        assertTrue(PsiSearchService.sameType("Errors", "Errors"));
        assertTrue(PsiSearchService.sameType("boolean", "boolean"));
        assertTrue(PsiSearchService.sameType("Optional<Deserializer<String>>", "Optional<Deserializer<String>>"));
    }

    // ---- negative cases: matcher must NOT collapse distinct types ----------

    @Test
    public void differentSimpleTypesDoNotMatch() {
        assertFalse(PsiSearchService.sameType("Time", "String"));
        assertFalse(PsiSearchService.sameType("KafkaClient", "SubscriptionState"));
    }

    @Test
    public void differentGenericArgumentsDoNotMatch() {
        assertFalse(PsiSearchService.sameType("List<String>", "List<Integer>"));
        assertFalse(PsiSearchService.sameType(
                "Map<CoordinatorKey,Map<MemberIdentity,Errors>>",
                "Map<CoordinatorKey,Map<TopicPartition,Errors>>"));
    }

    @Test
    public void sameSimpleNameDifferentArityGenericsDoNotMatch() {
        assertFalse(PsiSearchService.sameType("Optional<String>", "Optional<Deserializer<String>>"));
    }
}
