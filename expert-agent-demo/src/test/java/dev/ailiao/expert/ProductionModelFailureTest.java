package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dsh.contract.llm.ModelRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ProductionModelFailureTest {
    @Test void productionSelectsOneCompleteWorkbenchSummarySchemaAndBatchLocalPrelearnSources()throws Exception{
        AtomicReference<String> instruction=new AtomicReference<>();
        ModelRegistry registry=(config,signal)->CompletableFuture.completedFuture(new ModelRegistry.PreparedCall(){
            public Map<String,Object> config(){return config;}
            public Map<String,Object> adapterDefaults(){return Map.of();}
            public Map<String,Object> context(){return Map.of();}
            public String systemPromptUpdate(){return "in-history";}
            public Map<String,Object> retryPolicy(){return Map.of();}
            public Flux<Map<String,Object>> stream(Map<String,Object> request){
                var messages=(List<?>)request.get("messages");var system=(Map<?,?>)messages.get(0);
                var content=(List<?>)system.get("content");instruction.set((String)((Map<?,?>)content.get(0)).get("text"));
                return Flux.just(Map.of("type","text-delta","text","{}"),Map.of("type","finish","reason",Map.of("kind","stop")));
            }
        });
        ModelCalls calls=new ModelCalls(registry,"test-only","controlled-stream");
        calls.production("generate",Json.object().put("kind","book_summary").put("origin","workbench"),()->false);
        String workbench=instruction.get();
        assertTrue(workbench.contains("specialists:[{key,name,responsibility,methodTitles:[],typicalQuestions:[],sourceIds:[]}]"));
        assertFalse(workbench.contains("specialists:[{key,name,responsibility,methodTitles:[]}]}"));
        calls.production("generate",Json.object().put("kind","book_summary"),()->false);
        String legacy=instruction.get();assertTrue(legacy.contains("specialists:[{key,name,responsibility,methodTitles:[]}]}"));
        assertFalse(legacy.contains("typicalQuestions"));
        calls.production("prelearn",Json.object(),()->false);
        assertTrue(instruction.get().contains("passages.id"));
        assertTrue(instruction.get().contains("previousUnits"));
    }
    private ModelCalls calls(Flux<Map<String,Object>> chunks){
        ModelRegistry registry=(config,signal)->CompletableFuture.completedFuture(new ModelRegistry.PreparedCall(){
            public Map<String,Object> config(){return config;}
            public Map<String,Object> adapterDefaults(){return Map.of();}
            public Map<String,Object> context(){return Map.of();}
            public String systemPromptUpdate(){return "in-history";}
            public Map<String,Object> retryPolicy(){return Map.of();}
            public Flux<Map<String,Object>> stream(Map<String,Object> request){return chunks;}
        });return new ModelCalls(registry,"test-only","controlled-stream");
    }
    private ObjectNode details(Exception error)throws Exception{
        assertEquals("ProductionModelException",error.getClass().getSimpleName());var method=error.getClass().getDeclaredMethod("details");method.setAccessible(true);return (ObjectNode)method.invoke(error);
    }
    @Test void truncatedJsonPreservesSafeFinishKindAndBudgetInsteadOfGenericFailure()throws Exception{
        Exception error=assertThrows(Exception.class,()->calls(Flux.just(Map.of("type","text-delta","text","{\"secret-like-content\":\"never log this"),Map.of("type","finish","reason",Map.of("kind","max-tokens")))).production("prelearn",Json.object(),()->false));
        ObjectNode info=details(error);assertEquals("MODEL_OUTPUT_LIMIT",info.path("code").asText());assertEquals("max-tokens",info.path("finishKind").asText());assertTrue(info.path("maxOutputTokens").asInt()>0);assertFalse(info.toString().contains("secret-like-content"));assertFalse(error.getMessage().contains("never log"));
    }
    @Test void missingFinishAndMalformedJsonAreDistinctFailures()throws Exception{
        Exception incomplete=assertThrows(Exception.class,()->calls(Flux.just(Map.of("type","text-delta","text","{}"))).production("generate",Json.object(),()->false));
        assertEquals("MODEL_STREAM_INCOMPLETE",details(incomplete).path("code").asText());
        Exception malformed=assertThrows(Exception.class,()->calls(Flux.just(Map.of("type","text-delta","text","not-json-private-text"),Map.of("type","finish","reason",Map.of("kind","stop")))).production("generate",Json.object(),()->false));
        assertEquals("MODEL_JSON_INVALID",details(malformed).path("code").asText());assertFalse(malformed.getMessage().contains("private-text"));
    }
    @Test void transportFailureIsSafeAndCompleteJsonStillWorks()throws Exception{
        Exception network=assertThrows(Exception.class,()->calls(Flux.error(new java.io.IOException("private-network-details"))).production("generate",Json.object(),()->false));
        assertEquals("MODEL_TRANSPORT_FAILED",details(network).path("code").asText());assertFalse(network.getMessage().contains("private-network-details"));
        ObjectNode result=calls(Flux.just(Map.of("type","text-delta","text","{\"ok\":true}"),Map.of("type","finish","reason",Map.of("kind","stop")))).production("generate",Json.object(),()->false);assertTrue(result.path("ok").asBoolean());
    }
    @Test void invalidJsonReportsOnlySafeCategoryAndParserPosition()throws Exception{
        var cases=List.of(
            Map.entry("{\n\"private-key\": @}","SYNTAX"),
            Map.entry("{\"private-key\":", "UNEXPECTED_EOF"),
            Map.entry("[\"private-value\"]", "OBJECT_REQUIRED"),
            Map.entry("{} {}", "MAPPING"));
        for(var example:cases){
            Exception error=assertThrows(Exception.class,()->calls(Flux.just(Map.of("type","text-delta","text",example.getKey()),Map.of("type","finish","reason",Map.of("kind","stop")))).production("generate",Json.object(),()->false));
            ObjectNode info=details(error);assertEquals("MODEL_JSON_INVALID",info.path("code").asText());assertEquals(example.getValue(),info.path("jsonError").path("category").asText());
            assertFalse(info.toString().contains("private-key"));assertFalse(info.toString().contains("private-value"));assertNull(error.getCause());
            if(example.getValue().equals("SYNTAX")){assertEquals(2,info.path("jsonError").path("line").asInt());assertTrue(info.path("jsonError").path("column").asInt()>0);assertTrue(info.path("jsonError").path("charOffset").asLong()>0);}
        }
    }

}
