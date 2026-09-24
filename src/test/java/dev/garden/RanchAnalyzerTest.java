package dev.garden;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RanchAnalyzerTest {
 @Test void emptyEggCannotStartOrHatchWithoutEvidence()throws Exception {
  var state=Store.JSON.createObjectNode();state.putObject("self").putArray("materials");var p=RanchData.person(Store.JSON.createObjectNode().put("name","测试蛋"));state.putArray("people").add(p);
  try(var analyzer=new RanchAnalyzer(null,null,null)){
   assertThrows(IllegalArgumentException.class,()->analyzer.start(state,p.path("id").asText(),"profile",""));
   assertEquals("idle",analyzer.status().get("status"));assertEquals("egg",p.path("stage").asText());assertTrue(p.path("profile").isNull());
  }
 }
 @Test void staleProfileCannotDriveNewStrategy()throws Exception {
  var state=Store.JSON.createObjectNode();state.putObject("self").putArray("materials");var p=RanchData.person(Store.JSON.createObjectNode().put("name","测试"));p.withArray("materials").addObject().put("id","m").put("speaker","them").put("text","我喜欢散步");p.putObject("profile").put("summary","喜欢散步").put("stale",true);state.putArray("people").add(p);
  try(var analyzer=new RanchAnalyzer(null,null,null)){assertThrows(IllegalArgumentException.class,()->analyzer.start(state,p.path("id").asText(),"strategy","想约散步"));assertEquals("idle",analyzer.status().get("status"));}
 }
}
