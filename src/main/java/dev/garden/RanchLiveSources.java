package dev.garden;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;

/** On-demand platform directory and selected-chat snapshots. Never calls a model or persists chats. */
final class RanchLiveSources implements AutoCloseable {
 private record Choice(String platform,String rawId,String title,long time){}
 private record Snapshot(ObjectNode descriptor,ArrayNode messages,long time){}
 private final Function<Map<String,Object>,?> wechat,feishu;
 private final Map<String,Choice> choices=new LinkedHashMap<>();
 private final Map<String,Snapshot> snapshots=new LinkedHashMap<>();
 private final Set<Process> processes=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private volatile boolean closed;
 RanchLiveSources(Function<Map<String,Object>,?> feishu){this.wechat=this::runWechat;this.feishu=feishu;}
 RanchLiveSources(Function<Map<String,Object>,?> wechat,Function<Map<String,Object>,?> feishu){this.wechat=wechat;this.feishu=feishu;}
 ObjectNode list(String platform){
  if(!Set.of("wechat","feishu").contains(platform))throw new IllegalArgumentException("请选择微信或飞书。");
  var raw=response((platform.equals("wechat")?wechat:feishu).apply(Map.of("action","list")));
  var result=Store.JSON.createObjectNode();var out=result.putArray("conversations");var warnings=result.putArray("warnings");
  if(!raw.path("chats").isArray()||raw.path("chats").size()>10000)throw new IllegalArgumentException("平台会话列表无效，请刷新重试。");
  var names=new HashMap<String,Integer>();raw.path("chats").forEach(c->names.merge(c.path("title").asText(),1,Integer::sum));
  synchronized(this){
   long now=System.currentTimeMillis();choices.entrySet().removeIf(e->now-e.getValue().time()>600_000);
   int omitted=0;
   for(var c:raw.path("chats")){
    String key=c.path("id").asText(),title=c.path("title").asText();
    if(key.isBlank()||key.length()>512||title.isBlank()||title.length()>512)continue;
    if(platform.equals("feishu")&&names.get(title)>1){omitted++;continue;}
    String stable=platform.equals("wechat")?key:title;
    String id="live-"+platform+":"+UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8));
    choices.put(id,new Choice(platform,key,title,now));
    var d=out.addObject().put("id",id).put("title",title).put("platform",platform).put("live",true).put("isGroup",c.path("isGroup").asBoolean());d.putArray("members");
   }
   if(omitted>0)warnings.add("飞书有同名会话无法明确区分，已暂时略过；可先在客户端打开后通过聊天材料读取。");
  }
  return result.put("detail",raw.path("detail").asText(platform.equals("wechat")?"微信本机已同步的会话列表；选择后才读取该会话的消息。":"飞书客户端当前已加载的会话；选择后会在飞书中打开并读取当前加载的消息。"));
 }
 private synchronized Choice choice(String id){
  var c=choices.get(id);if(c==null||System.currentTimeMillis()-c.time()>600_000)throw new IllegalArgumentException("平台会话列表已过期，请刷新列表后重新选择。");return c;
 }
 ObjectNode members(String id){
  var c=choice(id);ObjectNode raw;
  if(c.platform().equals("wechat"))raw=response(wechat.apply(Map.of("action","history","chat",c.rawId())));
  else raw=response(feishu.apply(Map.of("action","select","chatId",c.rawId(),"chatTitle",c.title())));
  if(c.platform().equals("wechat")&&!c.rawId().equals(raw.path("chat").path("id").asText()))throw new IllegalArgumentException("会话身份已变化，请重新选择。");
  if(c.platform().equals("feishu")&&!c.title().equals(raw.path("chatTitle").asText()))throw new IllegalArgumentException("飞书没有打开所选会话，请重新选择。");
  var messages=normalize(raw,c.platform());
  var snapshotId=UUID.randomUUID().toString();
  var d=RanchSources.descriptor(id,c.platform(),c.title(),raw.path("detail").asText("本次读取的已加载消息，不代表完整历史。"),messages).put("live",true).put("snapshotId",snapshotId);
  synchronized(this){
   if(choices.get(id)!=c)throw new IllegalArgumentException("会话列表已刷新，请重新选择。");
   snapshots.put(snapshotId,new Snapshot(d,messages,System.currentTimeMillis()));
   while(snapshots.size()>20)snapshots.remove(snapshots.keySet().iterator().next());
  }
  return d.deepCopy();
 }
 synchronized ArrayNode materials(String id,String memberId,String snapshotId){
  var s=snapshots.get(snapshotId);
  if(s==null||!id.equals(s.descriptor().path("id").asText())||System.currentTimeMillis()-s.time()>600_000)throw new IllegalArgumentException("会话读取结果已过期，请重新选择会话和成员。");
  return RanchSources.select(id,s.descriptor().path("title").asText(),s.messages(),memberId);
 }
 static ArrayNode normalize(ObjectNode raw,String platform){
  var messages=Store.JSON.createArrayNode();var seen=new HashSet<String>();
  if(!raw.path("messages").isArray()||raw.path("messages").size()>1000)throw new IllegalArgumentException("消息结果无效，请重新读取。");
  for(var m:raw.path("messages")){
   String id=m.path(platform.equals("feishu")?"sourceId":"id").asText();if(id.isBlank()||!seen.add(id))continue;
   String text=m.path("text").asText();if(text.length()>24000)text=text.substring(0,23970)+"…【此条内容过长，已截断】";
   String name=m.path(platform.equals("feishu")?"sender":"memberName").asText("未知成员");
   String mid=platform.equals("feishu")?(name.equals("发言人未显示")?"unknown":"sender:"+name):m.path("memberId").asText("unknown");
   if(mid.isBlank())mid="unknown";
   var normalized=messages.addObject().put("id",id).put("text",text).put("memberId",mid).put("memberName",name)
    .put("isMe",platform.equals("wechat")&&m.path("isMe").asBoolean()).put("at",m.path("at").asText(raw.path("capturedAt").asText(Instant.now().toString())));
   if(RanchData.validMessageTime(m.path("at").asText()))normalized.put("spokenAt",m.path("at").asText());
  }
  return messages;
 }
 private ObjectNode response(Object value){
  if(closed)throw new IllegalArgumentException("平台读取服务已关闭。");
  JsonNode raw=Store.JSON.valueToTree(value);
  if(!raw.isObject()||!raw.path("status").asText().equals("ok"))throw new IllegalArgumentException(raw.path("detail").asText("暂时无法读取平台会话，请检查客户端后重试。"));
  return (ObjectNode)raw;
 }
 private Object runWechat(Map<String,Object> request){
  Path file=null;Process process=null;
  try{
   if(closed)throw new IllegalStateException();
   file=Files.createTempFile("ranch-wechat-",".json");
   var python=Path.of(System.getenv().getOrDefault("GARDEN_WECHAT_PYTHON","wechat-cli/.venv/bin/python")).toAbsolutePath();
   var script=Path.of(System.getenv().getOrDefault("GARDEN_WECHAT_READER","ranch_sources/wechat_reader.py")).toAbsolutePath();
   var command=new ArrayList<String>(List.of(python.toString(),script.toString(),request.get("action").toString()));
   if(request.get("action").equals("history"))command.addAll(List.of("--chat",request.get("chat").toString()));
   process=new ProcessBuilder(command).redirectOutput(file.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();processes.add(process);
   if(closed){process.destroyForcibly();throw new IllegalStateException();}
   if(!process.waitFor(55,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalArgumentException("读取微信超时，请稍后重试。");}
   if(process.exitValue()!=0||Files.size(file)>16_000_000)throw new IllegalArgumentException("微信读取未完成，请检查本机微信数据后重试。");
   return Store.JSON.readTree(Files.readAllBytes(file));
  }catch(IllegalArgumentException e){throw e;}
  catch(Exception e){throw new IllegalArgumentException("无法读取微信本机会话，请检查本地读取服务后重试。");}
  finally{if(process!=null){processes.remove(process);if(process.isAlive())process.destroyForcibly();}if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}}
 }
 @Override public void close(){closed=true;processes.forEach(Process::destroyForcibly);}
}
