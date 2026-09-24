package dev.garden;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RanchLiveSourcesTest {
 @Test void listUsesPlatformCatalogueBeyondPreviouslySavedConversations() {
  var response=Store.JSON.createObjectNode().put("status","ok");var chats=response.putArray("chats");
  for(int i=0;i<65;i++)chats.addObject().put("id","chat"+i).put("title","会话"+i);
  try(var live=new RanchLiveSources(r->response,r->response)){
   var list=live.list("wechat");assertEquals(65,list.path("conversations").size());
   assertTrue(list.path("conversations").get(0).path("live").asBoolean());
   assertEquals("wechat",list.path("conversations").get(0).path("platform").asText());
  }
 }
 @Test void onlyEnumeratedChoiceCanReadAndOnlyLoadedMemberCanImport()throws Exception {
  var calls=new AtomicInteger();
  try(var live=new RanchLiveSources(r->{calls.incrementAndGet();var out=Store.JSON.createObjectNode().put("status","ok");
   if(r.get("action").equals("list")){out.putArray("chats").addObject().put("id","raw-wx").put("title","朋友");}
   else{assertEquals("raw-wx",r.get("chat"));out.putObject("chat").put("id","raw-wx").put("title","朋友");out.putArray("messages").addObject().put("id","message-1").put("memberId","friend").put("memberName","朋友").put("text","你好").put("at","2026-09-24");}
   return out;
  },r->null)){
   assertThrows(IllegalArgumentException.class,()->live.members("guessed"));assertEquals(0,calls.get());
   String id=live.list("wechat").path("conversations").get(0).path("id").asText();
   assertThrows(IllegalArgumentException.class,()->live.materials(id,"friend",""));
   var members=live.members(id);var snapshot=members.path("snapshotId").asText();
   assertEquals("friend",members.path("members").get(0).path("id").asText());
   assertThrows(IllegalArgumentException.class,()->live.materials(id,"not-in-chat",snapshot));
   assertEquals("你好",live.materials(id,"friend",snapshot).get(0).path("text").asText());
  }
 }
 @Test void feishuVisibleNamesDoNotAllowAmbiguousDuplicateChats() {
  var response=Store.JSON.createObjectNode().put("status","ok");var chats=response.putArray("chats");
  chats.addObject().put("id","a").put("title","重名");chats.addObject().put("id","b").put("title","重名");chats.addObject().put("id","c").put("title","唯一");
  try(var live=new RanchLiveSources(r->null,r->response)){var list=live.list("feishu");assertEquals(1,list.path("conversations").size());assertEquals("唯一",list.path("conversations").get(0).path("title").asText());}
 }
 @Test void laterReadCannotReplaceTheTranscriptBoundToAnEarlierSelection() {
  var reads=new AtomicInteger();
  try(var live=new RanchLiveSources(r->{var out=Store.JSON.createObjectNode().put("status","ok");
   if(r.get("action").equals("list"))out.putArray("chats").addObject().put("id","c").put("title","测试");
   else{int n=reads.incrementAndGet();out.putObject("chat").put("id","c");out.putArray("messages").addObject().put("id","m"+n).put("text","第"+n+"次读取").put("memberId","p").put("memberName","成员");}return out;
  },r->null)){
   String id=live.list("wechat").path("conversations").get(0).path("id").asText();
   var first=live.members(id).path("snapshotId").asText();var second=live.members(id).path("snapshotId").asText();
   assertNotEquals(first,second);assertEquals("第1次读取",live.materials(id,"p",first).get(0).path("text").asText());
   assertEquals("第2次读取",live.materials(id,"p",second).get(0).path("text").asText());
   assertThrows(IllegalArgumentException.class,()->live.materials("different","p",first));
  }
 }
}
