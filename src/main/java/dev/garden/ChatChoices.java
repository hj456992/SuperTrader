package dev.garden;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** A selection must refer to a recent list actually returned by the plugin. */
final class ChatChoices {
 private record Choice(long time,String id,String title){}
 private final LinkedHashMap<String,Choice> choices=new LinkedHashMap<>();
 synchronized ObjectNode register(ObjectNode list){
  if(!"ok".equals(list.path("status").asText()))return list;
  choices.values().removeIf(c->System.currentTimeMillis()-c.time()>300_000);
  while(choices.size()>400)choices.remove(choices.keySet().iterator().next());
  var result=list.deepCopy();var out=result.putArray("chats");
  Map<String,Integer> counts=new HashMap<>();
  list.path("chats").forEach(c->counts.merge(c.path("title").asText(),1,Integer::sum));
  for(var c:list.path("chats")){
   String title=c.path("title").asText(),id=c.path("id").asText();
   if(counts.get(title)!=1)continue;
   String token=UUID.randomUUID().toString();choices.put(token,new Choice(System.currentTimeMillis(),id,title));
   out.addObject().put("choiceId",token).put("title",title);
  }
  if(out.size()<list.path("chats").size())result.put("detail","已加载客户端会话列表；同名会话已略过，可在飞书中打开后读取当前会话。");
  if(out.isEmpty())result.put("status","empty").put("detail","没有可明确区分的会话，请在飞书中打开目标群后读取当前会话。");
  return result;
 }
 synchronized Map<String,Object> request(String token){
  Choice c=choices.get(token);
  if(c==null || System.currentTimeMillis()-c.time()>300_000)throw new IllegalArgumentException("会话列表已过期，请重新加载列表。");
  return Map.of("action","select","chatId",c.id(),"chatTitle",c.title());
 }
}
