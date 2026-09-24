package dev.garden;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileToolBoundaryTest {
 @Test void laterChatObservationChangesOutputAndExtendsCitableIdsWithoutOtherConversations()throws Exception{
  var input=ProfileRuntimeTest.input("person");input.put("targetId","person-1");
  var state=Store.JSON.createObjectNode();state.putArray("library").addObject().put("enabled",true);
  var p=state.putArray("people").addObject().put("id","person-1");
  p.putArray("materials").addObject().put("id","M-counter").put("speaker","them").put("text","其实最近不喜欢散步").put("sourceKey","chat-A/member/M-counter");
  p.withArray("materials").addObject().put("id","M-unrelated").put("speaker","them").put("text","另一段对话").put("sourceKey","chat-B/member/M-unrelated");
  var calls=new AtomicInteger();
  try(var h=new ProfileRuntimeTest.Harness(request->{
   return switch(calls.incrementAndGet()){
    case 1->ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}");
    case 2->ProfileRuntimeTest.tool("chat_context","{\"id\":\"M-counter\"}");
    default->{var messages=request.get("messages").toString();assertTrue(messages.contains("M-counter"));assertFalse(messages.contains("M-unrelated"));assertTrue(messages.contains("newAllowedProfileEvidenceIds"));yield ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("偏好有变化，需要保留反证","K-demo-0").replace("\"counterEvidenceIds\":[]","\"counterEvidenceIds\":[\"M-counter\"]"));}
   };
  })){
   var result=new ProfileRuntime(h.context).generate(input,state,(q,l)->ProfileRuntimeTest.book(),"counter-test",()->false,(a,b,c)->{},a->{});
   assertEquals(3,calls.get());assertEquals("偏好有变化，需要保留反证",result.path("summary").asText());
  }
 }
 @Test void deletingBookRemovesCurrentAndHistoricalProfileQuotes()throws Exception{
  var repo=new RanchJobTest.MemoryRepository();var profile=(com.fasterxml.jackson.databind.node.ObjectNode)Store.JSON.readTree(ProfileRuntimeTest.profile("认识","K-demo-0"));profile.set("knowledge",ProfileRuntimeTest.book());repo.state.withObject("self").set("profile",profile);repo.state.withObject("self").putArray("analyses").add(profile.deepCopy());
  new RanchKnowledge(repo).command("knowledge-delete",Store.JSON.createObjectNode().put("revision",0).put("id","demo"));
  assertFalse(repo.read().path("self").path("profile").toString().contains("K-demo-0"));assertFalse(repo.read().path("self").path("analyses").toString().contains("K-demo-0"));
 }
}
