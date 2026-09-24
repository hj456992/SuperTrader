package dev.garden;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit release gates: run with -Dtest=ProfileAgentAcceptanceGates. Baseline is intentionally red. */
class ProfileAgentAcceptanceGates {
    @Test void profileModelInputMustBeIdenticalWhenOnlyRelationshipGoalChanges() throws Exception {
        var state = ProfileAgentAcceptanceTest.state();
        var before = RanchData.profileInput(RanchData.input(state, "person-lan"));
        RanchData.editPerson(RanchData.target(state, "person-lan"),
                Store.JSON.createObjectNode().put("goalType", "romance").put("goal", "想进一步了解"));
        var after = RanchData.profileInput(RanchData.input(state, "person-lan"));
        assertEquals(before, after, "Profile input must not expose relationship goals that can bias facts");
        assertFalse(after.has("goal"));
        assertFalse(after.has("goalType"));
    }
    @Test void profileMustRejectKnowledgeIdsThatWereNeverRead() throws Exception {
        var input = RanchData.profileInput(RanchData.input(ProfileAgentAcceptanceTest.state(), "person-lan"));
        input.putArray("knowledge");
        var output = ProfileAgentAcceptanceTest.result("M-lan-1");
        ((ObjectNode) output.path("facets").get(0)).putArray("knowledgeIds").add("K-never-read");
        assertThrows(IllegalArgumentException.class, () -> RanchData.validateProfile(output, input),
                "An invented method reference must fail independently of valid personal evidence");
    }
}
