package dev.garden;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Read-only assessment of pure domain functions using entirely synthetic in-memory data. */
public class AssessmentProbe {
    static ObjectNode state() {
        var state = Store.JSON.createObjectNode();
        state.putArray("people");
        state.putObject("self").putArray("materials");
        return state;
    }

    static ObjectNode person(ObjectNode state, String name) {
        var person = RanchData.person(Store.JSON.createObjectNode().put("name", name));
        state.withArray("people").add(person);
        return person;
    }

    public static void main(String[] args) throws Exception {
        var result = Store.JSON.createObjectNode();
        var state = state();
        var person = person(state, "Synthetic A");
        var additions = Store.JSON.createArrayNode();
        additions.addObject().put("id", "m1").put("sourceKey", "c/a/m1")
            .put("speaker", "them").put("text", "I am free on Sunday.");
        RanchData.linkSource(state, person.path("id").asText(), additions, "c", "a");
        person.putObject("profile").put("stale", false);
        ((ObjectNode) additions.get(0)).put("text", "Correction: I am not free on Sunday.");
        RanchData.linkSource(state, person.path("id").asText(), additions, "c", "a");
        result.put("same_source_id_correction_replaces_old_text", person.path("materials").get(0).path("text").asText().startsWith("Correction"));
        result.put("same_source_id_correction_marks_profile_stale", person.path("profile").path("stale").asBoolean());

        var secondState = state();
        ArrayNode messages = Store.JSON.createArrayNode();
        messages.addObject().put("id", "a1").put("memberId", "a").put("text", "A speaks");
        messages.addObject().put("id", "my1").put("memberId", "me").put("text", "My single statement").put("isMe", true);
        messages.addObject().put("id", "b1").put("memberId", "b").put("text", "B speaks");
        var a = person(secondState, "Synthetic A");
        var b = person(secondState, "Synthetic B");
        a.set("materials", RanchSources.select("c", "synthetic", messages, "a"));
        b.set("materials", RanchSources.select("c", "synthetic", messages, "b"));
        result.put("one_original_self_message_counted_after_two_member_links", RanchData.input(secondState, "self").path("materials").size());

        var input = Store.JSON.createObjectNode().put("target", "person");
        input.putArray("materials").addObject().put("id", "own").put("speaker", "them").put("text", "Please do not invite me again.");
        var output = Store.JSON.createObjectNode().put("summary", "They want another invitation.");
        output.putArray("uncertainties");
        output.putArray("facets").addObject().put("category", "preference").put("kind", "explicit")
            .put("text", "They want another invitation.").putArray("evidenceIds").add("own");
        boolean accepted = true;
        try { RanchData.validateProfile(output, input); }
        catch (IllegalArgumentException error) { accepted = false; }
        result.put("contradictory_claim_with_existing_evidence_id_passes_structural_validator", accepted);
        System.out.println(Store.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
