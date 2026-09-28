package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LabStoreTest {
    @TempDir Path dir;
    ObjectNode upload(LabStore store, String id, String text) throws Exception {
        return store.importDocument("沟通方法", id, Json.array().add(Json.object().put("page", 1).put("text", text)), "book.pdf", new byte[]{1,2,3});
    }
    ArrayNode select(ObjectNode doc) {
        return Json.array().add(Json.object().put("documentId",doc.path("documentId").asText()).put("versionId",doc.path("versionId").asText()));
    }
    ObjectNode expert(ArrayNode selection) {
        ObjectNode v=Json.object().put("name","职场专家").put("duty","帮助沟通");
        v.set("selections", selection); v.set("subagents",Json.array()); return v;
    }
    @Test void rejectsTwoVersionsOfTheSameDocument() throws Exception {
        LabStore store=new LabStore(dir);
        ObjectNode one=upload(store,"","先核实需求，再给建议。");
        ObjectNode two=upload(store,one.path("documentId").asText(),"先明确目标，再核实需求。");
        ArrayNode selections=select(one); selections.add(select(two).get(0));
        assertThrows(IllegalArgumentException.class,()->store.selectedChunks(selections));
    }
    @Test void versionSelectionReadsOnlyTheRequestedTextAndSurvivesRestart() throws Exception {
        LabStore store=new LabStore(dir);
        ObjectNode one=upload(store,"","第一版只谈直接表达。");
        upload(store,one.path("documentId").asText(),"第二版建议先确认关系背景。");
        LabStore reopened=new LabStore(dir);
        assertEquals("第一版只谈直接表达。",reopened.selectedChunks(select(one)).get(0).path("text").asText());
        assertEquals(2,reopened.state().path("documents").get(0).path("versions").size());
    }
    @Test void newDraftDoesNotChangeActiveVersionAndCannotActivateBeforeTrial() throws Exception {
        LabStore store=new LabStore(dir);
        ObjectNode doc=upload(store,"","核实事实，避免无依据推断。");
        ObjectNode first=store.saveExpert("",expert(select(doc)));
        String id=first.path("expertId").asText(), version=first.path("versionId").asText();
        assertThrows(IllegalArgumentException.class,()->store.activate(id,version));
        store.markTested(id,version); store.activate(id,version);
        store.saveExpert(id,expert(select(doc)));
        assertEquals(version,store.state().path("experts").get(0).path("activeVersionId").asText());
    }
    @Test void invalidVersionCannotReadAnything() throws Exception {
        LabStore store=new LabStore(dir);
        ObjectNode doc=upload(store,"","原文");
        ArrayNode selections=select(doc); ((ObjectNode)selections.get(0)).put("versionId","unknown");
        assertThrows(IllegalArgumentException.class,()->store.selectedChunks(selections));
    }
    @Test void snapshotsCannotBeMutatedThroughReturnedObjects() throws Exception {
        LabStore store=new LabStore(dir);
        ObjectNode doc=upload(store,"","原始内容");
        ((ObjectNode)store.selectedChunks(select(doc)).get(0)).put("text","被篡改");
        assertEquals("原始内容",store.selectedChunks(select(doc)).get(0).path("text").asText());
    }
    @Test void greetingDoesNotEnableAnUntestedVersion() throws Exception {
        LabStore store=new LabStore(dir);ObjectNode doc=upload(store,"","原文");ObjectNode saved=store.saveExpert("",expert(select(doc)));
        String id=saved.path("expertId").asText(), version=saved.path("versionId").asText();
        ObjectNode conversation=store.conversation("",id,version);
        conversation.withArray("messages").add(Json.object().put("role","assistant").put("content","你好").put("verifiedTrial",false));
        store.saveConversation(conversation);
        assertThrows(IllegalArgumentException.class,()->store.activate(id,version));
        assertThrows(IllegalArgumentException.class,()->store.conversation(conversation.path("id").asText(),id,"other-version"));
    }
    @Test void fullLongBookImportIsNotTruncatedAndReplayDoesNotDuplicateVersion() throws Exception {
        LabStore store=new LabStore(dir);
        String full="原文内容".repeat(70000);
        ArrayNode pages=Json.array().add(Json.object().put("page",1).put("text",full));
        ObjectNode provenance=Json.object().put("provider","mineru");
        ObjectNode first=store.importDocument("长书","",pages,"book.pdf",new byte[]{1,2,3},provenance,"import-1");
        ObjectNode again=store.importDocument("长书","",pages,"book.pdf",new byte[]{1,2,3},provenance,"import-1");
        assertEquals(first,again);assertEquals(1,store.state().path("documents").size());
        assertEquals(280000,store.state().path("documents").get(0).path("versions").get(0).path("charCount").asInt());
        var v=store.documentVersion(first.path("versionId").asText());
        StringBuilder kept=new StringBuilder();v.path("chunks").forEach(c->kept.append(c.path("text").asText()));
        assertEquals(full,kept.toString());assertEquals("mineru",v.path("parser").path("provider").asText());
        assertEquals(first,new LabStore(dir).importResult("import-1"));
    }
}
