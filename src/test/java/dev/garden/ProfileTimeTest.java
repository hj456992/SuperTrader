package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileTimeTest {
 @Test void initialInputKeepsTextAndConfirmedSpeechTimeButHidesRecordingTime() {
  var repo=new RanchJobTest.MemoryRepository();var self=(ObjectNode)repo.state.path("self");var materials=self.putArray("materials");
  var manual=RanchData.material(Store.JSON.createObjectNode().put("text","2024年夏天我去过海边").put("speaker","me"),true);materials.add(manual);
  materials.addObject().put("id","imported").put("speaker","me").put("text","这是一条有来源时间的原话").put("sourceKey","chat/member/imported").put("at","2026-09-24T01:00:00Z").put("spokenAt","2024-07-08T01:00:00Z");
  materials.addObject().put("id","old-import").put("speaker","me").put("text","旧导入").put("sourceKey","chat/member/old").put("at","2026-09-24T01:00:00Z");
  var original=repo.state.deepCopy();var input=RanchData.input(repo.state,"self");
  for(var m:input.path("materials")){
   assertFalse(m.has("at"),"operation time must not be sent to model");
   if(m.path("id").asText().equals("imported"))assertEquals("2024-07-08T01:00:00Z",m.path("spokenAt").asText());
   else assertFalse(m.has("spokenAt"));
  }
  assertTrue(input.toString().contains("2024年夏天我去过海边"));assertEquals(original,repo.state);assertTrue(manual.has("at"));
 }
 @Test void sourceNormalizationDoesNotPromoteCapturedTimeToSpeechTime(){
  var raw=Store.JSON.createObjectNode().put("capturedAt","2026-09-24T01:00:00Z");var messages=raw.putArray("messages");
  messages.addObject().put("id","genuine").put("memberId","p").put("memberName","人物").put("text","原话").put("at","2024-07-08T01:00:00Z");
  messages.addObject().put("id","captured").put("memberId","p").put("memberName","人物").put("text","没有日期");
  var normalized=RanchLiveSources.normalize(raw,"wechat");
  assertEquals("2024-07-08T01:00:00Z",normalized.get(0).path("spokenAt").asText());assertFalse(normalized.get(1).has("spokenAt"));
  var selected=RanchSources.select("live-wechat:chat","会话",normalized,"p");
  assertEquals("2024-07-08T01:00:00Z",selected.get(0).path("spokenAt").asText());assertFalse(selected.get(1).has("spokenAt"));
 }
 @Test void bothChatToolsHideManualAtAndKeepConfirmedSpeechTime()throws Exception{
  for(String tool:List.of("chat_search","chat_context")){
   var repo=new RanchJobTest.MemoryRepository();
   var m=(ObjectNode)repo.state.path("self").path("materials").get(0);m.put("at","2026-09-24T09:58:37Z");
   repo.state.withObject("self").withArray("materials").addObject().put("id","M-import").put("speaker","me").put("text","散步材料").put("sourceKey","chat/member/imported").put("at","2026-09-24T09:58:37Z").put("spokenAt","2024-07-08T01:00:00Z");
   var original=repo.state.deepCopy();var calls=new AtomicInteger();
   try(var h=new ProfileRuntimeTest.Harness(request->{
    int step=calls.incrementAndGet();var wire=request.get("messages").toString();assertFalse(wire.contains("2026-09-24T09:58:37Z"));assertTrue(wire.contains("2024-07-08T01:00:00Z"));
    if(step==1)return ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}");
    if(step==2)return ProfileRuntimeTest.tool(tool,tool.equals("chat_search")?"{\"query\":\"散步\"}":"{\"id\":\"M1\"}");
    assertTrue(wire.contains("tool-result"));return ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("日期未知的材料","K-demo-0"));
   })){
    new ProfileRuntime(h.context).generate(RanchData.profileInput(RanchData.input(repo.state,"self")),repo.state,(q,l)->ProfileRuntimeTest.book(),"time-"+tool,()->false,(a,b,c)->{},a->{});
    assertEquals(3,calls.get());assertEquals(original,repo.state);
   }
  }
 }
 @Test void contextRetainsRawOrderingWhenInitialInputContainsOnlyTheProjectedAnchor()throws Exception{
  var repo=new RanchJobTest.MemoryRepository();var materials=repo.state.withObject("self").putArray("materials");
  for(var id:List.of("late","early","M1")){
   String hour=id.equals("early")?"01":id.equals("M1")?"02":"03";
   materials.addObject().put("id",id).put("speaker","me").put("text","散步").put("sourceKey","chat/member/"+id).put("at","2026-09-24T"+hour+":00:00Z").put("spokenAt","2024-07-08T"+hour+":00:00Z");
  }
  var input=RanchData.profileInput(RanchData.input(repo.state,"self"));var anchor=input.path("materials").get(1).deepCopy();input.putArray("materials").add(anchor);
  var calls=new AtomicInteger();
  try(var h=new ProfileRuntimeTest.Harness(request->{
   int step=calls.incrementAndGet();if(step==1)return ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}");
   if(step==2)return ProfileRuntimeTest.tool("chat_context","{\"id\":\"M1\"}");
   com.fasterxml.jackson.databind.JsonNode results=null;
   for(var message:Store.JSON.valueToTree(request.get("messages")))for(var block:message.path("content"))if(block.path("type").asText().equals("tool-result"))for(var content:block.path("content"))try{
    var observation=Store.JSON.readTree(content.path("text").asText());if(observation.has("newAllowedProfileEvidenceIds"))results=observation.path("results");
   }catch(Exception e){throw new RuntimeException(e);}
   assertNotNull(results);assertEquals(List.of("early","M1","late"),java.util.stream.StreamSupport.stream(results.spliterator(),false).map(m->m.path("id").asText()).toList());
   for(var m:results)assertFalse(m.has("at"));
   return ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("时间有来源的有限认识","K-demo-0"));
  })){
   new ProfileRuntime(h.context).generate(input,repo.state,(q,l)->ProfileRuntimeTest.book(),"time-order",()->false,(a,b,c)->{},a->{});
  }
 }

}
