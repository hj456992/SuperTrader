package dev.garden;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Short-lived server-issued snapshots keep source attribution tied to actual reads. */
final class CaptureCache {
 private record Entry(long created, ObjectNode snapshot) {}
 private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
 private void prune(){
  long now=System.currentTimeMillis();
  entries.values().removeIf(e->now-e.created()>600_000);
 }
 synchronized ObjectNode register(ObjectNode snapshot){
  if(!"ok".equals(snapshot.path("status").asText()))return snapshot;
  prune();while(entries.size()>=16)entries.remove(entries.keySet().iterator().next());
  String id=UUID.randomUUID().toString();
  entries.put(id,new Entry(System.currentTimeMillis(),snapshot.deepCopy()));
  return snapshot.deepCopy().put("captureId",id);
 }
 synchronized void apply(JsonNode body,ObjectNode person){
  String id=body.path("captureId").asText("");if(id.isBlank())return;
  prune();Entry entry=entries.get(id);
  if(entry==null)throw new IllegalArgumentException("这次读取已过期，请重新读取飞书会话。");
  var s=entry.snapshot();
  if(!"feishu".equals(body.path("platform").asText()) || !s.path("chatTitle").asText().equals(body.path("group").asText()) || !s.path("text").asText().equals(body.path("text").asText()))
   throw new IllegalArgumentException("读取后内容已变化，请重新读取或作为手动内容保存。");
  if(person.path("messages").size()!=s.path("messages").size())throw new IllegalArgumentException("读取的消息边界发生变化，请重新读取。");
  person.put("source","feishu_desktop");
  var info=person.putObject("sourceInfo");
  for(String key:List.of("chatTitle","capturedAt","scope","truncated"))if(s.has(key))info.set(key,s.get(key).deepCopy());
  for(int i=0;i<s.path("messages").size();i++){
   var message=(ObjectNode)person.path("messages").get(i);
   message.put("sender",s.path("messages").get(i).path("sender").asText("发言人未显示"));
   message.put("source","feishu_desktop");
  }
 }
}
