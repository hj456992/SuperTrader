package dev.garden;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
class CaptureCacheTest {
 ObjectNode sample() throws Exception {return (ObjectNode)Store.JSON.readTree("""
 {"status":"ok","platform":"feishu","chatTitle":"群","text":"备注：甲：正文","scope":"current_view","capturedAt":"2026-09-23T01:00:00Z","messages":[{"sender":"甲","text":"正文","role":"note"}]}
 """);}
 @Test void preservesOriginOnlyForUnmodifiedCapture() throws Exception {
  var cache=new CaptureCache();var s=sample();var r=cache.register(s);
  var body=Store.JSON.createObjectNode().put("captureId",r.path("captureId").asText()).put("platform","feishu").put("group","群").put("text","备注：甲：正文");
  var person=Store.JSON.createObjectNode();person.putArray("messages").addObject().put("text","甲：正文");
  cache.apply(body,person);
  assertEquals("feishu_desktop",person.path("source").asText());
  assertEquals("甲",person.path("messages").get(0).path("sender").asText());
  assertEquals("current_view",person.path("sourceInfo").path("scope").asText());
  body.put("text","备注：被修改");assertThrows(IllegalArgumentException.class,()->cache.apply(body,person));
 }
 @Test void cannotForgeCaptureOrUseAnotherPlatform() throws Exception {
  var cache=new CaptureCache();var b=Store.JSON.createObjectNode().put("captureId","unknown");
  assertThrows(IllegalArgumentException.class,()->cache.apply(b,Store.JSON.createObjectNode()));
  b.put("captureId",cache.register(sample()).path("captureId").asText()).put("platform","wechat").put("group","群").put("text","备注：甲：正文");
  assertThrows(IllegalArgumentException.class,()->cache.apply(b,Store.JSON.createObjectNode()));
 }
}
