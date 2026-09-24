package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RanchJobTest {
 @Test void savedProfileCarriesRunIdentityAndProgressDoesNotInvalidateRevision()throws Exception {
  var repo=new MemoryRepository();var calls=new AtomicInteger();
  try(var h=new ProfileRuntimeTest.Harness(r->calls.incrementAndGet()==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"沟通\"}"):ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("当前情境","K-demo-0")));
      var analyzer=new RanchAnalyzer(h.context.require(dev.dsh.contract.llm.ModelRegistry.KEY),repo,new RanchKnowledge(repo),new ProfileRuntime(h.context))) {
   String id=analyzer.start(repo.read(),"self","profile","").get("id").toString();awaitDone(analyzer);
   assertEquals("done",analyzer.status().get("status"),analyzer.status().toString());
   var saved=repo.read().path("self").path("profile");assertEquals(id,saved.path("runId").asText());assertEquals(0,saved.path("basedOnRevision").asLong());assertEquals(1,saved.path("version").asInt());assertEquals(1,repo.read().path("revision").asInt());
   assertTrue(((List<?>)analyzer.status().get("events")).size()>=4);
  }
 }
 @Test void cancelDuringModelPreventsLateSaveAndOldRunCannotCancelCurrentRun()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var repo=new MemoryRepository();
  try(var h=new ProfileRuntimeTest.Harness(r->reactor.core.publisher.Flux.defer(()->{entered.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return ProfileRuntimeTest.answer(ProfileRuntimeTest.profile("迟到结果","K-demo-0"));}));
      var analyzer=new RanchAnalyzer(h.context.require(dev.dsh.contract.llm.ModelRegistry.KEY),repo,new RanchKnowledge(repo),new ProfileRuntime(h.context))) {
   String id=analyzer.start(repo.read(),"self","profile","").get("id").toString();assertTrue(entered.await(5,TimeUnit.SECONDS));
   analyzer.cancel("wrong-id");assertEquals("running",analyzer.status().get("status"));
   analyzer.cancel(id);analyzer.cancel(id);release.countDown();awaitDone(analyzer);
   assertEquals("cancelled",analyzer.status().get("status"));assertEquals(id,analyzer.status().get("id"));assertTrue(repo.read().path("self").path("profile").isMissingNode());assertEquals(0,repo.saves);
  }finally{release.countDown();}
 }
 @Test void unfinishedPersistedJobBecomesInterruptedWithoutBusinessRevisionChange()throws Exception{
  var repo=new MemoryRepository();repo.job=Store.JSON.createObjectNode().put("status","running").put("id","prior");
  try(var analyzer=new RanchAnalyzer(null,repo,new RanchKnowledge(repo),null)){assertEquals("interrupted",analyzer.status().get("status"));assertEquals("interrupted",repo.readJob().path("status").asText());assertEquals(0,repo.read().path("revision").asInt());}
 }
 static void awaitDone(RanchAnalyzer analyzer)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(Set.of("running","cancelling").contains(analyzer.status().get("status"))&&System.nanoTime()<end)Thread.sleep(10);assertFalse(Set.of("running","cancelling").contains(analyzer.status().get("status")),analyzer.status().toString());}
 static class MemoryRepository implements RanchRepository {
  ObjectNode state=Store.JSON.createObjectNode().put("revision",0),job=Store.JSON.createObjectNode().put("status","idle");int saves;
  MemoryRepository(){var self=state.putObject("self").put("about","").put("style","").put("boundaries","");self.set("materials",ProfileRuntimeTest.input("self").path("materials"));state.putArray("people");state.putArray("library").addObject().put("id","demo").put("enabled",true);}
  public synchronized ObjectNode read(){return state.deepCopy();}
  public synchronized ObjectNode update(long expected,Consumer<ObjectNode> mutation){if(expected!=state.path("revision").asLong())throw new IllegalStateException("stale");var next=state.deepCopy();mutation.accept(next);next.put("revision",expected+1);state=next;saves++;return read();}
  public synchronized ObjectNode readJob(){return job.deepCopy();}
  public synchronized void writeJob(ObjectNode next){job=next.deepCopy();}
  public ObjectNode book(String id){var b=Store.JSON.createObjectNode();b.set("chunks",ProfileRuntimeTest.book());return b;}
  public void putBook(String id,ObjectNode book){}
  public void deleteBook(String id){}
 }
}
