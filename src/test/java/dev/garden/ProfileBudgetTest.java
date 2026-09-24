package dev.garden;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileBudgetTest {
 @Test void emptyLibraryAndNoMatchHaveExplicitLimitedResults()throws Exception{
  for(boolean enabled:List.of(false,true)){
   var count=new AtomicInteger();try(var h=new ProfileRuntimeTest.Harness(r->count.incrementAndGet()==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}"):ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("有限认识","K-demo-0").replace("[\"K-demo-0\"]","[]")))){
    var state=Store.JSON.createObjectNode();state.putArray("library").addObject().put("enabled",enabled);
    var out=new ProfileRuntime(h.context).generate(ProfileRuntimeTest.input("self"),state,(q,l)->Store.JSON.createArrayNode(),"empty-"+enabled,()->false,(a,b,c)->{},a->{});
    assertEquals(enabled?"no_match":"empty_library",out.path("knowledgeStatus").asText());assertTrue(out.path("knowledge").isEmpty());assertEquals(2,out.path("uncertainties").size());
   }
  }
 }
 @Test void seventhModelCallIsBlockedAndNoPartialProfileReturned()throws Exception{
  var count=new AtomicInteger();try(var h=new ProfileRuntimeTest.Harness(r->{count.incrementAndGet();return ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}");})){
   var error=assertThrows(Exception.class,()->new ProfileRuntime(h.context).generate(ProfileRuntimeTest.input("self"),Store.JSON.createObjectNode(),(q,l)->ProfileRuntimeTest.book(),"budget",()->false,(a,b,c)->{},a->{}));
   assertEquals(6,count.get());assertTrue(error.getMessage().contains("6"),error.toString());
  }
 }
 @Test void stalledModelTimesOutWithoutPartialResult()throws Exception{
  var started=new java.util.concurrent.atomic.AtomicBoolean();
  try(var h=new ProfileRuntimeTest.Harness(r->{started.set(true);return reactor.core.publisher.Flux.never();})){
   assertThrows(java.util.concurrent.TimeoutException.class,()->new ProfileRuntime(h.context,Duration.ofSeconds(1)).generate(ProfileRuntimeTest.input("self"),Store.JSON.createObjectNode(),(q,l)->ProfileRuntimeTest.book(),"timeout",()->false,(a,b,c)->{},a->{}));
   assertTrue(started.get(),"timeout must exercise an actually subscribed silent model");
  }
 }
}
