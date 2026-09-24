package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dsh.contract.llm.ModelRegistry;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** Actual provider JAR's pure wire conversion; no credentials, stream(), HTTP or model requests. */
class ProfileAgentAcceptanceTransportTest {
    static final class Wire implements AutoCloseable {
        final URLClassLoader loader;
        final Method convert;
        final Map<String,Object> modelInfo;
        @SuppressWarnings("unchecked")
        Wire()throws Exception {
            var base=Path.of(System.getenv().getOrDefault("DSH_JAVA_HOME","/Users/hou/Documents/Codex/projects/dsh-java"));
            var artifact=base.resolve("plugins/model-deepseek/target/model-deepseek.jar");
            assertTrue(Files.isRegularFile(artifact),"Verified existing DeepSeek provider artifact required for transport acceptance");
            loader=new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()},getClass().getClassLoader());
            var adapter=Class.forName("dev.dsh.plugin.model.deepseek.application.DeepSeekAdapter",true,loader);
            convert=adapter.getDeclaredMethod("wireRequest",Map.class);convert.setAccessible(true);
            var catalog=(Map<String,Map<String,Object>>)Class.forName("dev.dsh.plugin.model.deepseek.application.DeepSeekCatalog",true,loader).getField("MODELS").get(null);
            modelInfo=Map.copyOf(catalog.get(Analyzer.MODEL));
            assertEquals(256000L,modelInfo.get("maxTokens"),"Catalog default is much larger: omission must not replace the 6000 budget");
        }
        JsonNode encode(Map<String,Object> request) {
            try{return Store.JSON.readTree(Store.JSON.writeValueAsString(convert.invoke(null,request)));}
            catch(Exception e){throw new AssertionError("Actual provider wire conversion failed",e);}
        }
        public void close()throws Exception {loader.close();}
    }
    static void assertBudget(JsonNode wire) {
        var max=wire.path("max_tokens");
        assertTrue(max.isIntegralNumber(),"Provider JSON max_tokens must be an integer; actual="+max);
        assertEquals(6000,max.asInt(),"Preserve explicit per-call budget; never inherit 256000");
    }
    static void install(ProfileRuntimeTest.Harness h,Wire wire,Function<Map<String,Object>,Flux<Map<String,Object>>> response) {
        h.context.own(h.context.require(ModelRegistry.KEY).registerAdapter("deepseek",(provider,name,signal)->CompletableFuture.completedFuture(new ModelRegistry.AdapterCall(){
            public Map<String,Object> model(){return wire.modelInfo;}
            public Flux<Map<String,Object>> stream(Map<String,Object> request){return response.apply(request);}
        }),Map.of(),Map.of()));
    }

    @Test void everySelfAndPersonProfileStepSerializesIntegerSixThousandToActualProvider()throws Exception {
        try(var wire=new Wire()) {
            for(String target:List.of("self","person-lan")) {
                var repo=new ProfileAgentAcceptanceRuntimeTest.Repo();var calls=new AtomicInteger();
                var captured=new ArrayList<JsonNode>();
                try(var h=new ProfileRuntimeTest.Harness(r->Flux.error(new AssertionError("Transport adapter replacement was not used")))) {
                    install(h,wire,r->{
                        var json=wire.encode(r);captured.add(json);assertBudget(json);
                        int step=calls.incrementAndGet();
                        assertEquals(Analyzer.MODEL,json.path("model").asText());assertFalse(json.path("tools").isEmpty());
                        return step==1?ProfileRuntimeTest.tool("book_search","{\"query\":\"倾听\"}"):ProfileRuntimeTest.answer(ProfileAgentAcceptanceRuntimeTest.output("QA传输验收",target.equals("self")?"M-self-direct":"M-lan-1",null,true).toString());
                    });
                    try(var a=ProfileAgentAcceptanceRuntimeTest.analyzer(h,repo)) {
                        a.start(repo.read(),target,"profile","");ProfileAgentAcceptanceRuntimeTest.awaitTerminal(a);
                        // Recheck captured wire outside asynchronous callbacks so a transport failure is explicit.
                        assertFalse(captured.isEmpty());captured.forEach(ProfileAgentAcceptanceTransportTest::assertBudget);
                        assertEquals("done",a.status().get("status"),a.status().toString());assertEquals(2,calls.get());assertEquals(1,repo.saves);
                    }
                }
            }
        }
    }

    @Test void actualProviderSerializerExposesFloatingPointAndInheritedBudgetHazards()throws Exception {
        try(var wire=new Wire()) {
            var request=new HashMap<String,Object>();request.put("model",Analyzer.MODEL);request.put("messages",List.of());
            request.put("maxTokens",6000d);
            assertFalse(wire.encode(request).path("max_tokens").isIntegralNumber(),"Control must reproduce actual 6000.0 encoding");
            request.put("maxTokens",wire.modelInfo.get("maxTokens"));
            assertEquals(256000,wire.encode(request).path("max_tokens").asInt());
            request.put("maxTokens",6000);
            assertBudget(wire.encode(request));
        }
    }
}
