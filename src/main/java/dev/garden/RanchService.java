package dev.garden;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Person-oriented commands. All content writes share the store's optimistic revision. */
final class RanchService {
 private final RanchStore store;private final RanchAnalyzer analyzer;private final RanchKnowledge knowledge;private final RanchSources sources;
 RanchService(RanchStore store,RanchAnalyzer analyzer,RanchKnowledge knowledge,RanchSources sources){this.store=store;this.analyzer=analyzer;this.knowledge=knowledge;this.sources=sources;}
 Object state() throws Exception {var state=store.read();state.set("job",Store.JSON.valueToTree(analyzer.status()));return state;}
 Object sources() throws Exception{return sources.catalog();}
 Object command(String action,JsonNode body)throws Exception{
  if("source-list".equals(action))return sources.platformCatalog(RanchData.text(body,"platform",30));
  if("source-members".equals(action))return sources.liveMembers(RanchData.text(body,"conversationId",200));
  if("cancel".equals(action)){analyzer.cancel(RanchData.text(body,"runId",100));return state();}
  if(!body.path("revision").canConvertToLong()||body.path("revision").asLong()<0)throw new IllegalArgumentException("缺少有效版本，请刷新。");
  long revision=body.path("revision").asLong();
  if(action.startsWith("knowledge-"))return knowledge.command(action,body);
  if(Set.of("analyze","strategy").contains(action)){
   var snapshot=store.read();if(snapshot.path("revision").asLong()!=revision)throw new IllegalStateException("资料已变化，请刷新。");
   return analyzer.start(snapshot,RanchData.text(body,"id",100),action.equals("analyze")?"profile":"strategy",RanchData.text(body,"situation",8000));
  }
  ArrayNode imported=null;
  if("link-source".equals(action)){
   if(store.read().path("revision").asLong()!=revision)throw new IllegalStateException("资料已变化，请刷新。");
   imported=sources.materials(RanchData.text(body,"conversationId",200),RanchData.text(body,"memberId",200),RanchData.text(body,"snapshotId",100));
  }
  final var additions=imported;
  store.update(revision,state->{
   if("person-create".equals(action)){if(state.path("people").size()>=200)throw new IllegalArgumentException("牧场最多保存200个人物。");state.withArray("people").add(RanchData.person(body));return;}
   if("self-update".equals(action)){
    var self=RanchData.target(state,"self");for(var key:List.of("name","about","style","boundaries"))if(body.has(key))self.put(key,RanchData.text(body,key,key.equals("name")?100:6000));
    RanchData.invalidate(self,true);RanchData.invalidateStrategies(state);return;
   }
   var id=RanchData.text(body,"id",100);var target=RanchData.target(state,id);boolean self="self".equals(id);
   switch(action){
    case "person-update" -> {if(self)throw new IllegalArgumentException("请使用自己的资料设置。");var oldNotes=target.path("notes").asText();RanchData.editPerson(target,body);if(!oldNotes.equals(target.path("notes").asText()))RanchData.invalidate(target,true);else for(var s:target.path("strategies"))((ObjectNode)s).put("stale",true);}
    case "person-delete" -> {if(self)throw new IllegalArgumentException("不能移除自己。");var people=state.withArray("people");for(int i=0;i<people.size();i++)if(id.equals(people.get(i).path("id").asText())){people.remove(i);break;}RanchData.invalidate(RanchData.target(state,"self"),true);RanchData.invalidateStrategies(state);}
    case "material-add" -> {if(target.path("materials").size()>=3000)throw new IllegalArgumentException("材料已达上限，请整理后添加。");var m=RanchData.material(body,self);target.withArray("materials").add(m);RanchData.invalidate(target,false);if(self||m.path("speaker").asText().equals("me")){RanchData.invalidate(RanchData.target(state,"self"),false);RanchData.invalidateStrategies(state);}}
    case "material-remove" -> {var materials=target.withArray("materials");var mid=RanchData.text(body,"materialId",100);boolean found=false;for(int i=0;i<materials.size();i++)if(mid.equals(materials.get(i).path("id").asText())){materials.remove(i);found=true;break;}if(!found)throw new IllegalArgumentException("找不到该材料。");RanchData.invalidate(target,true);RanchData.invalidate(RanchData.target(state,"self"),true);for(var person:state.path("people"))((ObjectNode)person).putArray("strategies");}
    case "link-source" -> {
     RanchData.linkSource(state,id,additions,RanchData.text(body,"conversationId",200),RanchData.text(body,"memberId",200));
    }
    default -> throw new IllegalArgumentException("未知牧场操作。");
   }
   target.put("updatedAt",RanchData.now());
  });return state();
 }
}
