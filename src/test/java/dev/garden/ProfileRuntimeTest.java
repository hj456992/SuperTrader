package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import dev.dsh.kernel.api.*;
import dev.dsh.kernel.runtime.*;
import dev.dsh.contract.llm.ModelRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileRuntimeTest {
 @Test void ownAndPersonProfilesUseRealToolObservationInSecondModelStep() throws Exception {
  for(String target:List.of("self","person")) {
   var calls=new AtomicInteger();
   try(var harness=new Harness(request->{
    int step=calls.incrementAndGet();
    if(step==1)return tool("book_search","{\"query\":\"沟通\"}");
    assertTrue(request.get("messages").toString().contains("避免把一次表达当成稳定偏好"));
    return answer(profile("仅在本次表达中喜欢散步","K-demo-0"));
   })) {
    var input=input(target);
    var runtime=new ProfileRuntime(harness.context);
    var state=Store.JSON.createObjectNode();state.putArray("library").addObject().put("enabled",true);
    var result=runtime.generate(input,state,
      (query,limit)->book(),"test-"+target,()->false,(phase,step,message)->{},a->{});
    assertEquals(2,calls.get());assertEquals("used",result.path("knowledgeStatus").asText());
    assertEquals("K-demo-0",result.path("knowledge").get(0).path("id").asText());
    assertEquals("仅在本次表达中喜欢散步",result.path("summary").asText());
   }
  }
 }
 static ObjectNode input(String target) {
  var input=Store.JSON.createObjectNode().put("target",target).put("notes","").put("about","").put("style","").put("boundaries","");
  input.putArray("materials").addObject().put("id","M1").put("speaker",target.equals("self")?"me":"them").put("text","今天想散步");
  input.putObject("coverage").put("used",1).put("total",1);
  return RanchData.profileInput(input);
 }
 static ArrayNode book(){var b=Store.JSON.createArrayNode();b.addObject().put("id","K-demo-0").put("documentId","demo").put("title","虚构沟通手册").put("location","第一节").put("text","避免把一次表达当成稳定偏好");return b;}
 static String profile(String summary,String knowledge){return "{\"summary\":\""+summary+"\",\"facets\":[{\"category\":\"近况\",\"text\":\"本次想散步，稳定偏好未知\",\"kind\":\"inferred\",\"evidenceIds\":[\"M1\"],\"knowledgeIds\":[\""+knowledge+"\"],\"counterEvidenceIds\":[]}],\"uncertainties\":[\"稳定偏好未知\"]}";}
 static Flux<Map<String,Object>> tool(String name,String arguments){return Flux.just(Map.of("type","block-start","index",0,"blockType","tool-call"),Map.of("type","tool-call-delta","index",0,"id","call-1","name",name,"argumentsDelta",arguments),Map.of("type","block-end","index",0,"block",Map.of("type","tool-call","id","call-1","name",name,"arguments",arguments)),Map.of("type","finish","reason",Map.of("kind","stop")));}
 static Flux<Map<String,Object>> answer(String text){return Flux.just(Map.of("type","block-start","index",0,"blockType","text"),Map.of("type","text-delta","index",0,"text",text),Map.of("type","block-end","index",0,"block",Map.of("type","text","text",text)),Map.of("type","finish","reason",Map.of("kind","stop")));}
 static final class Harness implements AutoCloseable {
  final PluginManager manager=new PluginManager();PluginContext context;
  Harness(Function<Map<String,Object>,Flux<Map<String,Object>>> model)throws Exception {
   var rows=new ArrayList<PluginRow>();
   rows.add(row("projections",new dev.dsh.plugin.session.projection.SessionProjectionPlugin()));
   rows.add(row("sessions",new dev.dsh.plugin.session.SessionPlugin(),"sessionProjections"));
   rows.add(row("agents",new dev.dsh.plugin.agent.AgentPlugin()));
   rows.add(row("context",new dev.dsh.plugin.context.ContextPlugin()));
   rows.add(row("tools",new dev.dsh.plugin.tools.ToolsPlugin(),"systemPrompt"));
   rows.add(row("models",new dev.dsh.plugin.model.registry.ModelRegistryPlugin()));
   rows.add(row("loop",new dev.dsh.plugin.agent.loop.AgentLoopPlugin(),"agents","sessions","sessionProjections","systemPrompt","tools","toolScheduler","llmRuntime"));
   rows.add(row("test",ctx->Mono.fromRunnable(()->{
    context=ctx;
    ctx.own(ctx.require(ModelRegistry.KEY).registerAdapter("deepseek",(provider,name,signal)->CompletableFuture.completedFuture(new ModelRegistry.AdapterCall(){
     public Map<String,Object> model(){return Map.of();}
     public Flux<Map<String,Object>> stream(Map<String,Object> request){return model.apply(request);}
    }),Map.of(),Map.of()));
   }),"agents","systemPrompt","tools","llmRuntime"));
   var started=manager.start(new BootstrapProfile(rows)).toCompletableFuture().get(10,TimeUnit.SECONDS);
   assertNotNull(context,started.describe().toString());
  }
  static PluginRow row(String id,Plugin plugin,String... inject){return new PluginRow(id,new PluginDefinition(id,"test",()->plugin),null,List.of(inject),Map.of(),true);}
  public void close()throws Exception{manager.dispose().toCompletableFuture().get(10,TimeUnit.SECONDS);}
 }
}
