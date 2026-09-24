package dev.garden;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
class RanchDataTest {
 private ObjectNode json(String s)throws Exception{return (ObjectNode)Store.JSON.readTree(s);}
 private ObjectNode input()throws Exception{return json("{\"target\":\"person\",\"materials\":[{\"id\":\"own\",\"speaker\":\"them\",\"text\":\"喜欢跑步\"},{\"id\":\"other\",\"speaker\":\"context\",\"text\":\"他很外向\"},{\"id\":\"mine\",\"speaker\":\"me\",\"text\":\"我喜欢猫\"}]}");}
 private ObjectNode profile(String evidence)throws Exception{return json("{\"summary\":\"喜欢跑步\",\"facets\":[{\"category\":\"兴趣\",\"text\":\"跑步\",\"kind\":\"explicit\",\"evidenceIds\":[\""+evidence+"\"]}],\"uncertainties\":[]}");}
 @Test void rejectsOtherSpeakersAsPersonalEvidence()throws Exception {assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(profile("other"),input()));assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(profile("mine"),input()));assertDoesNotThrow(()->RanchData.validateProfile(profile("own"),input()));}
 @Test void notesCannotMasqueradeAsObservedSpeech()throws Exception {var in=input();in.put("notes","用户说对方喜欢跑步");var out=profile("person-notes");assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(out,in));((ObjectNode)out.path("facets").get(0)).put("kind","inferred");assertDoesNotThrow(()->RanchData.validateProfile(out,in));}
 @Test void selfUsesOnlyOwnStatementsAndExplicitDescription()throws Exception {var in=input();in.put("target","self");in.put("about","我喜欢散步");assertDoesNotThrow(()->RanchData.validateProfile(profile("self-description"),in));assertDoesNotThrow(()->RanchData.validateProfile(profile("mine"),in));assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(profile("own"),in));}
 @Test void removingEvidenceRevokesCurrentAndHistoricalClaims()throws Exception {var p=RanchData.person(json("{\"name\":\"测试\"}"));p.set("profile",profile("own"));RanchData.history(p,"analyses",profile("own"));p.withArray("strategies").addObject().put("reply","测试回复");RanchData.invalidate(p,true);assertTrue(p.path("profile").isNull());assertTrue(p.path("analyses").isEmpty());assertTrue(p.path("strategies").isEmpty());}
 @Test void nameOnlyCreatesEggAndSelfInputIncludesMeMaterialsAcrossPeople()throws Exception {var state=json("{\"self\":{\"name\":\"我\",\"about\":\"\",\"style\":\"\",\"boundaries\":\"\",\"materials\":[]},\"people\":[]}");var p=RanchData.person(json("{\"name\":\"小雨\"}"));assertEquals("egg",p.path("stage").asText());assertTrue(p.path("profile").isNull());p.set("materials",input().path("materials"));state.withArray("people").add(p);var in=RanchData.input(state,"self");assertEquals(1,in.path("materials").size());assertEquals("mine",in.path("materials").get(0).path("id").asText());}
 @Test void coverageDisclosesOmittedMaterialsWithoutCuttingText()throws Exception {var state=json("{\"self\":{\"materials\":[]},\"people\":[]}");var p=RanchData.person(json("{\"name\":\"测试\"}"));for(int i=0;i<200;i++)p.withArray("materials").addObject().put("id","m"+i).put("speaker","them").put("text","原文".repeat(500));state.withArray("people").add(p);var in=RanchData.input(state,p.path("id").asText());assertEquals(200,in.path("coverage").path("total").asInt());assertTrue(in.path("coverage").path("used").asInt()<200);for(var m:in.path("materials"))assertEquals(1000,m.path("text").asText().length());}
 @Test void booksCannotBecomePersonalEvidenceAndUnknownBookCitationsFail()throws Exception {var in=input();var books=Store.JSON.createArrayNode();books.addObject().put("id","book-one");var strategy=json("{\"overview\":\"从共同兴趣开始\",\"steps\":[{\"title\":\"询问\",\"detail\":\"问一次开放问题\"}],\"reply\":\"\",\"why\":\"对方提到跑步\",\"evidenceIds\":[\"book-one\"],\"knowledgeIds\":[]}");assertThrows(IllegalArgumentException.class,()->RanchData.validateStrategy(strategy,in,books));strategy.putArray("evidenceIds").add("own");strategy.putArray("knowledgeIds").add("invented");assertThrows(IllegalArgumentException.class,()->RanchData.validateStrategy(strategy,in,books));strategy.putArray("knowledgeIds").add("book-one");assertDoesNotThrow(()->RanchData.validateStrategy(strategy,in,books));}
 @Test void staleProfileHistoriesCannotLookCurrentAfterNewMaterials()throws Exception {var p=RanchData.person(json("{\"name\":\"测试\"}"));p.set("profile",profile("own"));RanchData.history(p,"analyses",profile("own"));RanchData.invalidate(p,false);assertTrue(p.path("profile").path("stale").asBoolean());assertTrue(p.path("analyses").get(0).path("stale").asBoolean());}
 @Test void newestSelfMaterialSurvivesOlderStatementsFromLastPerson()throws Exception {
  var state=json("{\"self\":{\"materials\":[]},\"people\":[]}");
  state.withObject("self").withArray("materials").addObject().put("id","fresh-self").put("speaker","me").put("text","我现在喜欢安静相处").put("at","2026-09-24T00:00:00Z");
  var p=RanchData.person(json("{\"name\":\"测试\"}"));
  for(int i=0;i<160;i++)p.withArray("materials").addObject().put("id","old-"+i).put("speaker","me").put("text","较早发言").put("at","2025-01-01T00:00:00Z");
  state.withArray("people").add(p);var input=RanchData.input(state,"self");
  assertEquals(160,input.path("materials").size());assertEquals("fresh-self",input.path("materials").get(159).path("id").asText());assertEquals(161,input.path("coverage").path("total").asInt());
 }
 @Test void missingOrInvalidMaterialDatesUseStableOrder()throws Exception {
  var state=json("{\"self\":{\"materials\":[{\"id\":\"first\",\"speaker\":\"me\",\"text\":\"甲\"},{\"id\":\"second\",\"speaker\":\"me\",\"text\":\"乙\",\"at\":\"invalid\"},{\"id\":\"dated\",\"speaker\":\"me\",\"text\":\"丙\",\"at\":\"2026-09-24T00:00:00Z\"}]},\"people\":[]}");
  var in=RanchData.input(state,"self");assertEquals("first",in.path("materials").get(0).path("id").asText());assertEquals("second",in.path("materials").get(1).path("id").asText());assertEquals("dated",in.path("materials").get(2).path("id").asText());
 }
 @Test void repeatedSourceImportPreservesCurrentProfilesAndStrategiesUntilNewMaterialArrives()throws Exception {
  var state=json("{\"self\":{\"profile\":{\"stale\":false},\"materials\":[]},\"people\":[]}");var p=RanchData.person(json("{\"name\":\"测试\"}"));state.withArray("people").add(p);
  p.withArray("materials").addObject().put("id","m1").put("sourceKey","source1").put("speaker","them").put("text","旧发言");
  p.putObject("profile").put("stale",false);p.withArray("strategies").addObject().put("stale",false);p.withArray("sourceLinks").addObject().put("conversationId","c").put("memberId","member");
  var additions=p.withArray("materials").deepCopy();RanchData.linkSource(state,p.path("id").asText(),additions,"c","member");
  assertEquals(1,p.path("materials").size());assertFalse(p.path("profile").path("stale").asBoolean());assertFalse(state.path("self").path("profile").path("stale").asBoolean());assertFalse(p.path("strategies").get(0).path("stale").asBoolean());
  additions.addObject().put("id","m2").put("sourceKey","source2").put("speaker","them").put("text","新发言");RanchData.linkSource(state,p.path("id").asText(),additions,"c","member");
  assertEquals(2,p.path("materials").size());assertEquals(1,p.path("sourceLinks").size());assertTrue(p.path("profile").path("stale").asBoolean());assertTrue(p.path("strategies").get(0).path("stale").asBoolean());
 }
 @Test void profileRejectsMixedOwnAndOtherSpeakerReferences()throws Exception {
  for(var other:java.util.List.of("mine","other")){
   var out=profile("own");((ObjectNode)out.path("facets").get(0)).withArray("evidenceIds").add(other);
   assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(out,input()));
  }
  var in=input();in.put("target","self");var out=profile("mine");((ObjectNode)out.path("facets").get(0)).withArray("evidenceIds").add("own");
  assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(out,in));
 }
 @Test void profilePayloadSeparatesContextWhileStrategyInputKeepsAllRoles()throws Exception {
  var original=input();var payload=RanchData.profileInput(original);
  assertEquals(1,payload.path("materials").size());assertEquals("own",payload.path("materials").get(0).path("id").asText());assertEquals(2,payload.path("conversationContext").size());
  assertEquals("own",payload.path("allowedProfileEvidenceIds").get(0).asText());assertEquals(1,payload.path("allowedProfileEvidenceIds").size());assertEquals(3,original.path("materials").size());
 }
}
