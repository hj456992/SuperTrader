package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** Pure ranch state transitions and evidence validation, shared by persistence and generation. */
final class RanchData {
 static String now(){return Instant.now().toString();}
 static String id(){return UUID.randomUUID().toString();}
 static ObjectNode target(ObjectNode state,String id){
  if("self".equals(id)) return (ObjectNode)state.path("self");
  for(var p:state.path("people")) if(id.equals(p.path("id").asText()))return (ObjectNode)p;
  throw new IllegalArgumentException("找不到这个人物，请刷新。");
 }
 static String text(JsonNode body,String key,int limit){
  if(body.has(key)&&!body.path(key).isTextual())throw new IllegalArgumentException("字段格式不正确："+key);
  var s=body.path(key).asText(""); if(s.length()>limit)throw new IllegalArgumentException("内容过长："+key);return s;
 }
 static String choice(JsonNode body,String key,String fallback,String... options){
  var value=text(body,key,80);if(value.isEmpty())value=fallback;
  if(!Set.of(options).contains(value))throw new IllegalArgumentException("选项不正确："+key);return value;
 }
 static ObjectNode person(JsonNode body){
  var p=Store.JSON.createObjectNode();p.put("id",id());p.put("stage","egg");p.put("createdAt",now());
  p.putArray("materials");p.putArray("strategies");p.putArray("analyses");p.putArray("sourceLinks");p.putNull("profile");editPerson(p,body);return p;
 }
 static void editPerson(ObjectNode p,JsonNode body){
  var name=body.has("name")?text(body,"name",100).trim():p.path("name").asText();
  if(name.isEmpty())throw new IllegalArgumentException("请填写称呼。");p.put("name",name);
  for(var key:List.of("goal","notes"))if(body.has(key)||!p.has(key))p.put(key,text(body,key,6000));
  if(body.has("goalType")||!p.has("goalType"))p.put("goalType",choice(body,"goalType","friendship","friendship","romance"));
  if(body.has("creature")||!p.has("creature"))p.put("creature",choice(body,"creature","rabbit","rabbit","cat","fox","bear"));
  if(body.has("color")||!p.has("color"))p.put("color",choice(body,"color","sage","sage","lilac","peach","sky"));
  p.put("updatedAt",now());
 }
 static void invalidateStrategies(ObjectNode state){for(var p:state.path("people"))for(var s:p.path("strategies"))((ObjectNode)s).put("stale",true);}
 static void invalidate(ObjectNode p,boolean revoke){
  if(revoke){p.putNull("profile");p.putArray("analyses");p.putArray("strategies");}
  else{for(var prior:p.path("analyses"))((ObjectNode)prior).put("stale",true);if(p.path("profile").isObject())((ObjectNode)p.path("profile")).put("stale",true);for(var s:p.path("strategies"))((ObjectNode)s).put("stale",true);}
 }
 static ObjectNode material(JsonNode body,boolean self){
  var text=text(body,"text",24000);if(text.isBlank())throw new IllegalArgumentException("请先填写材料原文。");
  var m=Store.JSON.createObjectNode();m.put("id",id());m.put("text",text);
  m.put("speaker",choice(body,"speaker",self?"me":"them","them","me","context"));
  if(self&&m.path("speaker").asText().equals("them"))throw new IllegalArgumentException("自己的材料请选择我或背景。");
  m.put("label",text(body,"label",200));m.put("at",now());return m;
 }
 /** Include newest complete materials under a character budget; never truncate away speaker attribution. */
 static ObjectNode input(ObjectNode state,String id){
  var p=target(state,id);boolean self="self".equals(id);var out=Store.JSON.createObjectNode();
  out.put("targetId",id);out.put("target",self?"self":"person");out.put("name",p.path("name").asText());
  if(self){for(var key:List.of("about","style","boundaries"))out.set(key,p.path(key));}
  else{out.put("notes",p.path("notes").asText());out.put("goalType",p.path("goalType").asText());out.put("goal",p.path("goal").asText());}
  var all=new ArrayList<JsonNode>();p.path("materials").forEach(all::add);
  if(self)for(var person:state.path("people"))for(var m:person.path("materials"))if(m.path("speaker").asText().equals("me"))all.add(m);
  all.sort(Comparator.comparing(RanchData::materialTime));
  var seen=new HashSet<String>();var selected=new ArrayList<JsonNode>();int chars=0;
  for(int i=all.size()-1;i>=0;i--){var m=all.get(i);if(!seen.add(m.path("id").asText()))continue;
   if(selected.size()>=160||chars+m.toString().length()>42000)continue;selected.add(m);chars+=m.toString().length();}
  Collections.reverse(selected);var materials=out.putArray("materials");selected.forEach(materials::add);
  out.putObject("coverage").put("used",selected.size()).put("total",all.size());return out;
 }
 /** Missing/invalid timestamps sort before dated materials; stable sort preserves ties. */
 private static Instant materialTime(JsonNode material){
  try{return Instant.parse(material.path("at").asText());}
  catch(java.time.format.DateTimeParseException ignored){return Instant.MIN;}
 }
 static Set<String> ownEvidence(ObjectNode input){
  var own=new HashSet<String>();boolean self=input.path("target").asText().equals("self");
  for(var m:input.path("materials"))if(m.path("speaker").asText().equals(self?"me":"them"))own.add(m.path("id").asText());
  if(self&&(!input.path("about").asText().isBlank()||!input.path("style").asText().isBlank()||!input.path("boundaries").asText().isBlank()))own.add("self-description");
  if(!self&&!input.path("notes").asText().isBlank())own.add("person-notes");return own;
 }
 /** Physically separates non-target context from citable profile evidence. */
 static ObjectNode profileInput(ObjectNode input){
  var result=input.deepCopy();result.remove(List.of("goal","goalType"));var own=ownEvidence(input);var materials=result.putArray("materials");var context=result.putArray("conversationContext");
  for(var material:input.path("materials")){if(own.contains(material.path("id").asText()))materials.add(material);else context.add(material);}
  var ids=result.putArray("allowedProfileEvidenceIds");own.stream().sorted().forEach(ids::add);return result;
 }
 static Set<String> allEvidence(ObjectNode input){var all=ownEvidence(input);for(var m:input.path("materials"))all.add(m.path("id").asText());return all;}
 static void string(JsonNode n,String key,int max,boolean required){if(!n.path(key).isTextual()||n.path(key).asText().length()>max||(required&&n.path(key).asText().isBlank()))throw new IllegalArgumentException("invalid "+key);}
 static void references(JsonNode ids,Set<String> allowed,Set<String> required){
  if(!ids.isArray()||ids.size()>40)throw new IllegalArgumentException("invalid evidenceIds");boolean own=false;
  for(var id:ids){if(!id.isTextual()||!allowed.contains(id.asText()))throw new IllegalArgumentException("unknown evidenceIds");if(required.contains(id.asText()))own=true;}
  if(!required.isEmpty()&&!own)throw new IllegalArgumentException("missing own evidence");
 }
 static void validateProfile(ObjectNode result,ObjectNode input){validateProfile(result,input,Store.JSON.createArrayNode());}
 static void validateProfile(ObjectNode result,ObjectNode input,ArrayNode knowledge){
  var knownBooks=new HashSet<String>();knowledge.forEach(k->knownBooks.add(k.path("id").asText()));
  string(result,"summary",1600,true);var facets=result.path("facets");
  if(!facets.isArray()||facets.isEmpty()||facets.size()>12)throw new IllegalArgumentException("invalid facets");
  var own=ownEvidence(input);if(own.isEmpty())throw new IllegalArgumentException("missing personal evidence");
  for(var f:facets){string(f,"category",80,true);string(f,"text",600,true);
   if(!Set.of("explicit","inferred").contains(f.path("kind").asText()))throw new IllegalArgumentException("invalid kind");references(f.path("evidenceIds"),own,own);
   if(f.has("knowledgeIds"))references(f.path("knowledgeIds"),knownBooks,Set.of());
   if(f.has("counterEvidenceIds"))references(f.path("counterEvidenceIds"),own,Set.of());
   for(var field:List.of("scope","confidenceReason"))if(f.has(field))string(f,field,800,false);
   if(f.path("evidenceIds").toString().contains("person-notes")&&f.path("kind").asText().equals("explicit"))throw new IllegalArgumentException("user report must be inferred");
  }
  if(!result.path("uncertainties").isArray()||result.path("uncertainties").size()>20)throw new IllegalArgumentException("invalid uncertainties");
  for(var u:result.path("uncertainties"))if(!u.isTextual()||u.asText().length()>800)throw new IllegalArgumentException("invalid uncertainty");
 }
 static void validateStrategy(ObjectNode result,ObjectNode input,ArrayNode knowledge){
  for(var key:List.of("overview","why"))string(result,key,2400,true);string(result,"reply",2400,false);
  if(!result.path("steps").isArray()||result.path("steps").isEmpty()||result.path("steps").size()>10)throw new IllegalArgumentException("invalid steps");
  for(var s:result.path("steps")){string(s,"title",150,true);string(s,"detail",1600,true);}
  var own=ownEvidence(input);var all=allEvidence(input);
  if(input.path("self").isObject())all.addAll(allEvidence((ObjectNode)input.path("self")));
  references(result.path("evidenceIds"),all,own);
  var ids=new HashSet<String>();knowledge.forEach(k->ids.add(k.path("id").asText()));references(result.path("knowledgeIds"),ids,Set.of());
 }
 static void history(ObjectNode target,String key,ObjectNode result){var array=target.withArray(key);array.add(result.deepCopy());while(array.size()>20)array.remove(0);}
 static void linkSource(ObjectNode state,String id,ArrayNode additions,String cid,String member){
  var target=target(state,id);boolean self="self".equals(id);
     var keys=new HashSet<String>();target.path("materials").forEach(m->{if(m.hasNonNull("sourceKey"))keys.add(m.path("sourceKey").asText());});
     boolean added=false;
     for(var original:additions){var m=(ObjectNode)original.deepCopy();var key=m.path("sourceKey").asText();if(key.isBlank())throw new IllegalArgumentException("导入材料缺少稳定标识。");if(!keys.add(key))continue;if(target.path("materials").size()>=3000)throw new IllegalArgumentException("材料已达上限。");if(self&&m.path("speaker").asText().equals("them"))m.put("speaker","me");else if(self&&m.path("speaker").asText().equals("me"))m.put("speaker","context");target.withArray("materials").add(m);added=true;}
     var links=target.withArray("sourceLinks");boolean exists=false;for(var l:links)if(cid.equals(l.path("conversationId").asText())&&member.equals(l.path("memberId").asText()))exists=true;
     if(!exists)links.addObject().put("conversationId",cid).put("memberId",member);
     if(added){RanchData.invalidate(target,false);RanchData.invalidate(RanchData.target(state,"self"),false);RanchData.invalidateStrategies(state);}
 }
}
