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
 @Test void cancellingSilentModelReleasesProviderSubscriptionBeforeReportingTerminal()throws Exception {
  var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var repo=new MemoryRepository();
  var source=new AtomicReference<reactor.core.publisher.FluxSink<Map<String,Object>>>();
  try(var h=new ProfileRuntimeTest.Harness(r->reactor.core.publisher.Flux.<Map<String,Object>>create(sink->{
    sink.onCancel(cancelled::countDown);source.set(sink);entered.countDown();
   }));var analyzer=new RanchAnalyzer(h.context.require(dev.dsh.contract.llm.ModelRegistry.KEY),repo,new RanchKnowledge(repo),new ProfileRuntime(h.context))) {
   analyzer.start(repo.read(),"self","profile","");assertTrue(entered.await(5,TimeUnit.SECONDS));
   try {
    analyzer.cancel();assertTrue(cancelled.await(2,TimeUnit.SECONDS),"Cancellation must reach the actual provider subscription");
    awaitDone(analyzer);assertEquals("cancelled",analyzer.status().get("status"));assertEquals(0,repo.saves);
   }finally{if(source.get()!=null)source.get().complete();}
  }
 }
 @Test void unfinishedPersistedJobBecomesInterruptedWithoutBusinessRevisionChange()throws Exception{
  var repo=new MemoryRepository();repo.job=Store.JSON.createObjectNode().put("status","running").put("id","prior");
  try(var analyzer=new RanchAnalyzer(null,repo,new RanchKnowledge(repo),null)){assertEquals("interrupted",analyzer.status().get("status"));assertEquals("interrupted",repo.readJob().path("status").asText());assertEquals(0,repo.read().path("revision").asInt());}
 }
 @Test void strategyResultAddsRunIdentityWithoutRewritingLegacyHistoryOrProfile()throws Exception {
  var repo=new StrategyExecutionTest.StrategyRepository();var person=(ObjectNode)repo.state.path("people").get(0);
  for(int i=0;i<20;i++)person.withArray("strategies").addObject().put("id","legacy-"+i).put("reply","既有建议"+i);
  var old=repo.read();var models=strategyModels(request->StrategyExecutionTest.answer("P1"));
  try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))){
   String run=analyzer.start(repo.read(),"person-1","strategy","邀请散步").get("id").toString();awaitDone(analyzer);
   assertEquals("done",analyzer.status().get("status"));assertEquals(1,repo.saves);
   var state=repo.read();var saved=state.path("people").get(0).path("strategies");assertEquals(20,saved.size());
   for(int i=0;i<19;i++)assertEquals(old.path("people").get(0).path("strategies").get(i+1),saved.get(i));
   assertEquals(run,saved.get(19).path("runId").asText());assertEquals(0,saved.get(19).path("basedOnRevision").asLong());
   assertEquals(old.path("people").get(0).path("profile"),state.path("people").get(0).path("profile"));
   assertEquals(1,state.path("revision").asInt());
  }
 }
 @Test void strategyCancelAfterFullResponseBeforeValidationKeepsAllOldBusinessState()throws Exception {
  var repo=new StrategyExecutionTest.StrategyRepository();var before=repo.read();var entered=new CountDownLatch(1);
  var source=new AtomicReference<reactor.core.publisher.FluxSink<Map<String,Object>>>();
  var models=strategyModels(request->reactor.core.publisher.Flux.create(sink->{source.set(sink);entered.countDown();}));
  try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))){
   try {
    analyzer.start(repo.read(),"person-1","strategy","邀请散步");assertTrue(entered.await(2,TimeUnit.SECONDS));
    synchronized(analyzer){
     StrategyExecutionTest.answer("P1").doOnNext(source.get()::next).blockLast();source.get().complete();
     analyzer.cancel();
    }
    awaitDone(analyzer);assertEquals("cancelled",analyzer.status().get("status"));assertEquals(0,repo.saves);assertEquals(before,repo.read());
   }finally{analyzer.cancel();if(source.get()!=null)source.get().complete();awaitDone(analyzer);}
  }
 }
 @Test void strategySavingFirstKeepsDoneWhenCancellationWaitsForMonitor()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var repo=new StrategyExecutionTest.StrategyRepository(){
   @Override public synchronized ObjectNode update(long expected,Consumer<ObjectNode> mutation){entered.countDown();awaitGate(release);return super.update(expected,mutation);}
  };
  var models=strategyModels(request->StrategyExecutionTest.answer("P1"));
  try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))){
   analyzer.start(repo.read(),"person-1","strategy","邀请散步");assertTrue(entered.await(2,TimeUnit.SECONDS));
   var cancel=new Thread(analyzer::cancel);cancel.start();
   try {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(cancel.getState()!=Thread.State.BLOCKED&&System.nanoTime()<end)Thread.sleep(5);
    assertEquals(Thread.State.BLOCKED,cancel.getState());release.countDown();cancel.join(2000);assertFalse(cancel.isAlive());
    awaitDone(analyzer);assertEquals("done",analyzer.status().get("status"));assertEquals(1,repo.saves);
   }finally{release.countDown();cancel.join(2000);}
  }finally{release.countDown();}
 }
 @Test void strategyRevisionConflictRejectsResultWithoutOverwritingOtherWrite()throws Exception {
  var entered=new CountDownLatch(1);var source=new AtomicReference<reactor.core.publisher.FluxSink<Map<String,Object>>>();
  var repo=new StrategyExecutionTest.StrategyRepository();
  var models=strategyModels(request->reactor.core.publisher.Flux.create(sink->{source.set(sink);entered.countDown();}));
  try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo))){
   try {
    analyzer.start(repo.read(),"person-1","strategy","邀请散步");assertTrue(entered.await(2,TimeUnit.SECONDS));
    repo.update(0,state->state.put("unrelatedUpdate",true));
    StrategyExecutionTest.answer("P1").doOnNext(source.get()::next).blockLast();source.get().complete();awaitDone(analyzer);
    assertEquals("error",analyzer.status().get("status"));assertEquals(1,repo.saves);
    assertTrue(repo.read().path("unrelatedUpdate").asBoolean());assertTrue(repo.read().path("people").get(0).path("strategies").isEmpty());
   }finally{analyzer.cancel();if(source.get()!=null)source.get().complete();awaitDone(analyzer);}
  }
 }
 @Test void strategyChecksDeadlineInsideDelayedRepositoryMutation()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var repo=new StrategyExecutionTest.StrategyRepository(){
   @Override public synchronized ObjectNode update(long expected,Consumer<ObjectNode> mutation){entered.countDown();awaitGate(release);return super.update(expected,mutation);}
  };
  var models=strategyModels(request->StrategyExecutionTest.answer("P1"));
  try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo),null,new StrategyRuntime(models,java.time.Duration.ofMillis(250)))){
   try {
    analyzer.start(repo.read(),"person-1","strategy","邀请散步");assertTrue(entered.await(2,TimeUnit.SECONDS));
    Thread.sleep(350);release.countDown();awaitDone(analyzer);
    assertEquals("error",analyzer.status().get("status"));assertEquals(0,repo.saves);assertEquals(0,repo.read().path("revision").asInt());
   }finally{release.countDown();}
  }
 }
 @Test void strategyExpiredOrCancelledRetrievalNeverPreparesModel()throws Exception {
  for(boolean cancel:List.of(false,true)){
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var prepares=new AtomicInteger();
   var repo=new StrategyExecutionTest.StrategyRepository(){
    @Override public ObjectNode book(String id){entered.countDown();awaitGate(release);return super.book(id);}
   };
   repo.state.withArray("library").addObject().put("id","demo").put("enabled",true);
   var models=StrategyExecutionTest.registry((provider,model,signal)->{prepares.incrementAndGet();return CompletableFuture.completedFuture(StrategyExecutionTest.adapter(request->StrategyExecutionTest.answer("P1")));});
   try(var analyzer=new RanchAnalyzer(models,repo,new RanchKnowledge(repo),null,new StrategyRuntime(models,java.time.Duration.ofMillis(250)))){
    try {
     analyzer.start(repo.read(),"person-1","strategy","邀请散步");assertTrue(entered.await(2,TimeUnit.SECONDS));
     if(cancel)analyzer.cancel();else Thread.sleep(350);
     release.countDown();awaitDone(analyzer);assertEquals(cancel?"cancelled":"error",analyzer.status().get("status"));assertEquals(0,prepares.get());assertEquals(0,repo.saves);
    }finally{release.countDown();}
   }
  }
 }
 private static dev.dsh.contract.llm.ModelRegistry strategyModels(java.util.function.Function<Map<String,Object>,reactor.core.publisher.Flux<Map<String,Object>>> source){
  return StrategyExecutionTest.registry((provider,model,signal)->CompletableFuture.completedFuture(StrategyExecutionTest.adapter(source)));
 }
 private static void awaitGate(CountDownLatch gate){try{if(!gate.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test gate not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
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
