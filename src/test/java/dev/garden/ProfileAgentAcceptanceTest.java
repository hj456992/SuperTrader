package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent QA assertions against production data/retrieval code; no network or database. */
class ProfileAgentAcceptanceTest {
    static ObjectNode fixture() throws Exception {
        return (ObjectNode) Store.JSON.readTree(Path.of("tests/acceptance/fixtures/profile-agent.json").toFile());
    }
    static ObjectNode state() throws Exception { return (ObjectNode) fixture().path("state"); }
    static ObjectNode result(String evidence) throws Exception {
        var result = (ObjectNode) fixture().path("legacyProfile");
        ((ObjectNode) result.path("facets").get(0)).putArray("evidenceIds").add(evidence);
        return result;
    }
    static Set<String> ids(JsonNode values) {
        var ids = new HashSet<String>();
        values.forEach(v -> ids.add(v.isTextual() ? v.asText() : v.path("id").asText()));
        return ids;
    }
    @Test void personEvidenceExcludesOtherPersonUserAndBackground() throws Exception {
        var input = RanchData.profileInput(RanchData.input(state(), "person-lan"));
        assertEquals(Set.of("M-lan-1", "M-lan-counter"), ids(input.path("materials")));
        assertEquals(Set.of("M-me-in-lan", "M-context"), ids(input.path("conversationContext")));
        for (var invalid : Set.of("M-yu-1", "M-self-direct", "M-context", "M-me-in-lan", "K-qa-method-0", "invented")) {
            assertThrows(IllegalArgumentException.class, () -> RanchData.validateProfile(result(invalid), input), invalid);
        }
        var mixed = result("M-lan-1");
        ((ObjectNode) mixed.path("facets").get(0)).withArray("evidenceIds").add("M-yu-1");
        assertThrows(IllegalArgumentException.class, () -> RanchData.validateProfile(mixed, input));
    }
    @Test void selfCollectsOwnStatementsAcrossPeopleWithoutStealingTheirStatements() throws Exception {
        var input = RanchData.profileInput(RanchData.input(state(), "self"));
        assertEquals(Set.of("M-self-direct", "M-me-in-lan", "M-me-in-yu"), ids(input.path("materials")));
        for (var own : ids(input.path("materials"))) assertDoesNotThrow(() -> RanchData.validateProfile(result(own), input));
        for (var other : Set.of("M-lan-1", "M-lan-counter", "M-yu-1", "M-context", "person-notes"))
            assertThrows(IllegalArgumentException.class, () -> RanchData.validateProfile(result(other), input));
    }
    @Test void userReportCannotBecomeDirectObservation() throws Exception {
        var input = RanchData.profileInput(RanchData.input(state(), "person-lan"));
        var report = result("person-notes");
        assertThrows(IllegalArgumentException.class, () -> RanchData.validateProfile(report, input));
        ((ObjectNode) report.path("facets").get(0)).put("kind", "inferred").put("text", "据用户描述，最近工作忙。");
        assertDoesNotThrow(() -> RanchData.validateProfile(report, input));
    }
    @Test void conflictingStatementIsAvailableWithoutSilentlyDroppingIt() throws Exception {
        var input = RanchData.profileInput(RanchData.input(state(), "person-lan"));
        assertTrue(ids(input.path("materials")).containsAll(Set.of("M-lan-1", "M-lan-counter")));
        assertEquals(4, input.path("coverage").path("used").asInt());
        assertEquals(4, input.path("coverage").path("total").asInt());
    }
    @Test void bookRetrievalReturnsActualFictionalSourceAndNoMatchStaysEmpty() throws Exception {
        var f = fixture();
        var chunks = RanchKnowledge.chunk("qa-method", f.path("book").path("title").asText(),
                (com.fasterxml.jackson.databind.node.ArrayNode) f.path("book").path("pages"));
        var found = RanchKnowledge.rank(chunks, f.path("loopOracle").path("bookQuery").asText(), 3);
        assertFalse(found.isEmpty());
        assertTrue(found.size() <= 3); // Title terms may also match other passages in this book.
        assertEquals("qa-method", found.get(0).path("documentId").asText());
        assertEquals("虚构第1节", found.get(0).path("location").asText());
        assertEquals(f.path("book").path("pages").get(0).path("text"), found.get(0).path("text"));
        assertTrue(RanchKnowledge.rank(chunks, "量子纠缠黑洞", 3).isEmpty());
        assertTrue(RanchKnowledge.rank(Store.JSON.createArrayNode(), "倾听", 3).isEmpty());
    }
    @Test void changingGoalDoesNotRewriteSavedFactsAtDataLayer() throws Exception {
        var person = RanchData.target(state(), "person-lan");
        var original = result("M-lan-1");
        person.set("profile", original.deepCopy());
        var evidence = person.path("materials").deepCopy();
        RanchData.editPerson(person, Store.JSON.createObjectNode().put("goalType", "romance").put("goal", "想进一步了解"));
        assertEquals(original, person.path("profile"));
        assertEquals(evidence, person.path("materials"));
    }
    @Test void legacyProfileWithoutAnyNewFieldsStillValidates() throws Exception {
        var legacy = (ObjectNode) fixture().path("legacyProfile");
        var before = legacy.deepCopy();
        assertDoesNotThrow(() -> RanchData.validateProfile(legacy, RanchData.profileInput(RanchData.input(state(), "person-lan"))));
        assertEquals(before, legacy);
    }
    @Test void revocationRemovesCurrentAndRetainedEvidenceClaims() throws Exception {
        var person = RanchData.target(state(), "person-lan");
        person.set("profile", result("M-lan-1"));
        RanchData.history(person, "analyses", result("M-lan-1"));
        person.withArray("strategies").addObject().putArray("evidenceIds").add("M-lan-1");
        RanchData.invalidate(person, true);
        assertTrue(person.path("profile").isNull());
        assertTrue(person.path("analyses").isEmpty());
        assertTrue(person.path("strategies").isEmpty());
    }
}
