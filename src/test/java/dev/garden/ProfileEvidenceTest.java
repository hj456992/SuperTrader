package dev.garden;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.*;
class ProfileEvidenceTest {
 @Test void goalsDoNotEnterAnyProfileInput(){var in=ProfileRuntimeTest.input("person");in.put("goal","浪漫").put("goalType","romance");var first=RanchData.profileInput(in);in.put("goal","朋友").put("goalType","friendship");assertEquals(first,RanchData.profileInput(in));assertFalse(first.has("goal"));}
 @Test void inventedBookOrCounterEvidenceCannotPassValidation()throws Exception{
  var input=ProfileRuntimeTest.input("person");
  var result=(ObjectNode)Store.JSON.readTree(ProfileRuntimeTest.profile("认识","K-never-read"));
  assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(result,input));
  ((ObjectNode)result.path("facets").get(0)).putArray("knowledgeIds");
  ((ObjectNode)result.path("facets").get(0)).putArray("counterEvidenceIds").add("OTHER");
  assertThrows(IllegalArgumentException.class,()->RanchData.validateProfile(result,input));
 }
}
