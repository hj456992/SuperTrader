package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Distinguishes manual collection time from source event time without changing stored chronology. */
class ProfileAgentAcceptanceTimeTest {
    static final String MANUAL_AT="2026-09-24T08:09:10Z";
    static final String SOURCE_AT="2024-02-03T10:11:12Z";
    static ObjectNode material(String id,String speaker,String at,boolean imported) {
        var m=Store.JSON.createObjectNode().put("id",id).put("speaker",speaker).put("text","时间验收：我喜欢先确认安排。").put("at",at);
        if(imported)m.put("sourceKey","wechat:fictional-chat/fictional-member/"+id).put("spokenAt",at);
        return m;
    }
    static ProfileAgentAcceptanceRuntimeTest.Repo repo(String target)throws Exception {
        var repo=new ProfileAgentAcceptanceRuntimeTest.Repo();
        repo.state.withObject("self").putArray("materials");
        repo.state.path("people").forEach(p->((ObjectNode)p).putArray("materials"));
        var materials=RanchData.target(repo.state,target).withArray("materials");String own=target.equals("self")?"me":"them";
        materials.add(material("qa-manual",own,MANUAL_AT,false));
        materials.add(material("qa-background","context",MANUAL_AT,false));
        materials.add(material("qa-source",own,SOURCE_AT,true));
        var fallback=material("qa-source-fallback",own,MANUAL_AT,true);fallback.remove("spokenAt");materials.add(fallback);
        return repo;
    }
    static JsonNode byId(JsonNode values,String id) {
        for(var v:values)if(v.path("id").asText().equals(id))return v;
        fail("Expected actual material "+id);return null;
    }
    static void noFalseEventDate(JsonNode m) {
        assertFalse(m.hasNonNull("at"),"Manual at is collection time and must not reach the model as event time: "+m);
        assertFalse(m.hasNonNull("spokenAt"),"Unconfirmed source must not gain spokenAt");
        assertNotEquals(MANUAL_AT,m.path("sentAt").asText());assertNotEquals(MANUAL_AT,m.path("eventAt").asText());
    }
    static void collectObservations(JsonNode n,List<JsonNode> observations) {
        if(n.isTextual()&&n.asText().startsWith("{"))try {
            var parsed=Store.JSON.readTree(n.asText());if(parsed.path("results").isArray())observations.add(parsed.path("results"));
        }catch(Exception ignored){}
        if(n.isContainerNode())n.forEach(v->collectObservations(v,observations));
    }

    @Test void initialProfileProjectionDistinguishesTimeProvenanceAndDoesNotMutateStoredMaterials()throws Exception {
        for(String target:List.of("self","person-lan")) {
            var repo=repo(target);var before=repo.read();var input=RanchData.input(repo.state,target);
            var originalInput=input.deepCopy();var profile=RanchData.profileInput(input);
            assertAll(
                ()->noFalseEventDate(byId(profile.path("materials"),"qa-manual")),
                ()->noFalseEventDate(byId(profile.path("conversationContext"),"qa-background")),
                ()->noFalseEventDate(byId(profile.path("materials"),"qa-source-fallback")),
                ()->assertFalse(byId(profile.path("materials"),"qa-source").hasNonNull("at")),
                ()->assertEquals(SOURCE_AT,byId(profile.path("materials"),"qa-source").path("spokenAt").asText()),
                ()->assertEquals(originalInput,input,"projection must not mutate its input"),
                ()->assertEquals(before,repo.read(),"stored at and source metadata must stay unchanged")
            );
        }
    }

    @Test void actualInitialMessagesAndBothChatToolsKeepTheSameTimeBoundary()throws Exception {
        for(String target:List.of("self","person-lan")) {
            var repo=repo(target);var before=repo.read();var input=RanchData.profileInput(RanchData.input(repo.state,target));
            var calls=new AtomicInteger();var requests=new ArrayList<JsonNode>();
            try(var h=new ProfileRuntimeTest.Harness(request->{
                requests.add(Store.JSON.valueToTree(request.get("messages")));
                return switch(calls.incrementAndGet()) {
                    case 1->ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}");
                    case 2->ProfileRuntimeTest.tool("chat_search","{\"query\":\"时间验收\"}");
                    case 3->ProfileRuntimeTest.tool("chat_context","{\"id\":\"qa-manual\"}");
                    case 4->ProfileRuntimeTest.tool("chat_context","{\"id\":\"qa-source\"}");
                    default->ProfileRuntimeTest.answer(ProfileAgentAcceptanceRuntimeTest.output("时间未知，不能推定同一天发言。","qa-manual",null,true).toString());
                };
            })) {
                new ProfileRuntime(h.context).generate(input,repo.read(),(q,l)->new RanchKnowledge(repo).retrieve(repo.read(),q,l),"qa-time-"+target,()->false,(p,s,m)->{},a->{});
                assertEquals(5,calls.get());
                var observations=new ArrayList<JsonNode>();collectObservations(requests.get(4),observations);
                var manual=new ArrayList<JsonNode>();var imported=new ArrayList<JsonNode>();
                for(var results:observations)for(var m:results) {
                    if(Set.of("qa-manual","qa-background","qa-source-fallback").contains(m.path("id").asText()))manual.add(m);
                    if(m.path("id").asText().equals("qa-source"))imported.add(m);
                }
                assertTrue(manual.size()>=3,"search must include manual+background and context must include manual anchor");
                assertTrue(imported.size()>=2,"search and source context must both return source material");
                assertAll(
                    ()->assertFalse(requests.get(0).toString().contains(MANUAL_AT),"actual initial model message must not invent a speech date"),
                    ()->manual.forEach(ProfileAgentAcceptanceTimeTest::noFalseEventDate),
                    ()->imported.forEach(m->{assertFalse(m.hasNonNull("at"));assertEquals(SOURCE_AT,m.path("spokenAt").asText());}),
                    ()->assertEquals(before,repo.read(),"tools must not rewrite stored timestamps or raw materials")
                );
            }
        }
    }

    @Test void droppingManualEventDatesDoesNotBreakInternalNewestMaterialSelection()throws Exception {
        var repo=repo("self");var materials=repo.state.withObject("self").withArray("materials");materials.removeAll();
        materials.add(material("qa-newest-manual","me",MANUAL_AT,false));
        for(int i=0;i<160;i++)materials.add(material("qa-old-"+i,"me","2023-01-01T00:00:00Z",false));
        var before=repo.read();var projected=RanchData.profileInput(RanchData.input(repo.state,"self"));
        assertEquals(160,projected.path("materials").size());
        assertEquals("qa-newest-manual",projected.path("materials").get(159).path("id").asText());
        assertEquals(before,repo.read());
        noFalseEventDate(byId(projected.path("materials"),"qa-newest-manual"));
    }
    @Test void liveNormalizationAndSelectionOnlyPreserveExplicitValidMessageTime()throws Exception {
        for(String platform:List.of("wechat","feishu")) {
            var raw=Store.JSON.createObjectNode().put("capturedAt",MANUAL_AT);
            var messages=raw.putArray("messages");
            for(String id:List.of("real","missing","invalid")) {
                var m=messages.addObject().put("id",id).put("sourceId",id).put("memberId","member").put("memberName","虚构源人物").put("sender","虚构源人物").put("text","时间验收"+id);
                if(id.equals("real"))m.put("at",SOURCE_AT);if(id.equals("invalid"))m.put("at","not-a-date");
            }
            var before=raw.deepCopy();var normalized=RanchLiveSources.normalize(raw,platform);
            var selected=RanchSources.select("live-"+platform+":fictional","虚构来源",normalized,platform.equals("wechat")?"member":"sender:虚构源人物");
            assertEquals(3,selected.size());
            for(var m:selected) {
                if(m.path("sourceKey").asText().endsWith("/real"))assertEquals(SOURCE_AT,m.path("spokenAt").asText());
                else assertFalse(m.hasNonNull("spokenAt"),"captured/invalid timestamps are not confirmed speech time");
            }
            assertEquals(before,raw,"source extraction must not mutate the captured source");
        }
    }

    @Test void logCaptureFallbackAndLegacyImportedTimeAreNotPromotedToSpeechTime()throws Exception {
        var log=Store.JSON.createObjectNode();var rows=log.putArray("segments").addObject().putArray("messages");
        rows.addObject().put("id","log-real").put("memberId","member").put("text","确证日期原文").put("sentAt",SOURCE_AT).put("capturedAt",MANUAL_AT);
        rows.addObject().put("id","log-fallback").put("memberId","member").put("text","未知日期原文").put("capturedAt",MANUAL_AT);
        var method=RanchSources.class.getDeclaredMethod("logMessages",ObjectNode.class);method.setAccessible(true);
        var normalized=(ArrayNode)method.invoke(null,log);
        assertEquals(SOURCE_AT,byId(normalized,"log-real").path("spokenAt").asText());
        assertFalse(byId(normalized,"log-fallback").hasNonNull("spokenAt"));
        var legacy=Store.JSON.createObjectNode().put("id","qa-legacy").put("name","旧来源");
        legacy.putArray("messages").addObject().put("id","legacy-1").put("role","me").put("text","旧原文没有日期").put("importedAt",MANUAL_AT);
        var before=legacy.deepCopy();var legacyMethod=RanchSources.class.getDeclaredMethod("legacyMessages",ObjectNode.class);legacyMethod.setAccessible(true);
        var imported=(ArrayNode)legacyMethod.invoke(null,legacy);
        assertEquals(1,imported.size());assertFalse(imported.get(0).hasNonNull("spokenAt"));assertEquals(before,legacy);
    }

}
