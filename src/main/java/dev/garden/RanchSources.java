package dev.garden;

import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicitly selected identities from legacy conversations and read-only local logs. */
final class RanchSources {
    private final Store legacy;
    private final RanchLiveSources live;
    RanchSources(Store legacy){this(legacy,null);}
    RanchSources(Store legacy,RanchLiveSources live){this.legacy=legacy;this.live=live;}

    ObjectNode catalog() throws Exception {
        var result=Store.JSON.createObjectNode();var list=result.putArray("conversations");var warnings=result.putArray("warnings");
        for(var p:legacy.list()) {
            list.add(legacyDescriptor(p));
        }
        return result;
    }
    ObjectNode platformCatalog(String platform) throws Exception {
        return platformCatalog(platform,legacy.list(),()->live.list(platform));
    }
    static ObjectNode platformCatalog(String platform,java.util.List<ObjectNode> saved,java.util.function.Supplier<ObjectNode> fetch) {
        if(!Set.of("wechat","feishu","other").contains(platform))throw new IllegalArgumentException("请选择平台。");
        ObjectNode result=Store.JSON.createObjectNode();
        if(!platform.equals("other"))try{result=fetch.get();result.put("liveAvailable",true);}
        catch(IllegalArgumentException error){result.put("liveAvailable",false).put("detail","平台会话目录暂不可用；以下仅显示明确标注的已保存记录，可刷新重试。");result.putArray("warnings").add(error.getMessage());}
        var list=result.withArray("conversations");
        for(var p:saved) {
            var d=legacyDescriptor(p);if(!platform.equals(d.path("platform").asText()))continue;
            d.put("live",false);list.add(d);
        }
        return result;
    }
    ObjectNode liveMembers(String id){return live.members(id);}
    ArrayNode materials(String conversationId,String memberId) throws Exception {return materials(conversationId,memberId,"");}
    ArrayNode materials(String conversationId,String memberId,String snapshotId) throws Exception {
        if(conversationId.startsWith("live-"))return live.materials(conversationId,memberId,snapshotId);
        if(conversationId.startsWith("legacy:")) {
            var p=legacy.get(conversationId.substring(7));
            return select(conversationId,p.path("name").asText(),legacyMessages(p),memberId);
        }
        if(conversationId.equals("logbook:primary")) {
            var log=logbook();return select(conversationId,log.path("group").path("name").asText(),logMessages(log),memberId);
        }
        throw new IllegalArgumentException("请选择可用的聊天来源。");
    }
    static ObjectNode legacyDescriptor(ObjectNode person) {
        var messages=legacyMessages(person);
        var platform=person.path("platform").asText();
        if(!Set.of("wechat","feishu").contains(platform))platform="other";
        return descriptor("legacy:"+person.path("id").asText(),platform,person.path("name").asText(),"当前会话已保存的 "+messages.size()+" 条原文；按用户选定的成员关联。",messages);
    }
    static ObjectNode descriptor(String id,String platform,String title,String scope,ArrayNode messages) {
        var d=Store.JSON.createObjectNode().put("id",id).put("title",title).put("platform",platform).put("scope",scope);
        var members=new LinkedHashMap<String,ObjectNode>();
        for(var m:messages) {
            var mid=m.path("memberId").asText();
            if(mid.isBlank()||mid.equals("unknown"))continue;
            var v=members.computeIfAbsent(mid,key->Store.JSON.createObjectNode().put("id",key).put("name",m.path("memberName").asText(mid)).put("count",0));
            v.put("count",v.path("count").asInt()+1);
        }
        var array=d.putArray("members");members.values().forEach(array::add);return d;
    }
    private static ArrayNode legacyMessages(ObjectNode p) {
        var result=Store.JSON.createArrayNode();var context=AssistantState.describe(p);
        var owners=new HashMap<String,ObjectNode>();
        for(var member:context.path("members"))for(var id:member.path("messageIds"))owners.put(id.asText(),(ObjectNode)member);
        for(var m:p.path("messages")) {
            var owner=owners.get(m.path("id").asText());var mid=owner==null?"unknown":owner.path("id").asText();
            boolean me="me".equals(m.path("role").asText())||mid.equals(context.path("selfId").asText());
            if(me)mid="me";
            result.addObject().put("id",m.path("id").asText()).put("text",m.path("text").asText()).put("memberId",mid)
                .put("memberName",me?"我的发言（已标注）":owner==null?m.path("sender").asText("背景备注"):owner.path("name").asText())
                .put("isMe",me).put("at",m.path("importedAt").asText());
        }
        return result;
    }
    private static ObjectNode logbook() throws Exception {
        var token=Path.of(System.getenv().getOrDefault("GARDEN_LOGBOOK_TOKEN","../../work/chatlog/service.token"));
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:48742/v1/state")).timeout(Duration.ofSeconds(8))
            .header("X-Logbook-Token",Files.readString(token).strip()).GET().build();
        var response=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            if(response.statusCode()!=200)throw new IllegalArgumentException("聊天日志暂时不可用。");
            var bytes=body.readNBytes(16_000_001);if(bytes.length>16_000_000)throw new IllegalArgumentException("日志响应过大。");
            var log=(ObjectNode)Store.JSON.readTree(bytes);
            if(!"卧底不追高".equals(log.path("group").path("name").asText()))throw new IllegalArgumentException("日志来源未确认。");
            return log;
        }
    }
    private static ArrayNode logMessages(ObjectNode log) {
        var all=new ArrayList<ObjectNode>();var seen=new HashSet<String>();
        for(var segment:log.path("segments"))for(var m:segment.path("messages")) {
            if(!seen.add(m.path("id").asText()))continue;
            var normalized=Store.JSON.createObjectNode().put("id",m.path("id").asText()).put("memberId",m.path("memberId").asText())
                .put("memberName",m.path("author").asText()).put("text",m.path("text").asText())
                .put("at",m.path("sentAt").asText(m.path("capturedAt").asText())).put("isMe",false).put("order",m.path("createTime").asLong());
            if(RanchData.validMessageTime(m.path("sentAt").asText()))normalized.put("spokenAt",m.path("sentAt").asText());
            all.add(normalized);
        }
        all.sort(Comparator.comparingLong(m->m.path("order").asLong()));var result=Store.JSON.createArrayNode();all.forEach(result::add);return result;
    }
    static ArrayNode select(String conversationId,String label,ArrayNode messages,String memberId) {
        if(memberId.isBlank()||memberId.equals("unknown"))throw new IllegalArgumentException("请选择有明确身份的发言人。");
        var selected=new TreeSet<Integer>();int count=0;
        for(int i=messages.size()-1;i>=0&&count<300;i--)if(memberId.equals(messages.get(i).path("memberId").asText())) {
            count++;selected.add(i);if(i>0)selected.add(i-1);if(i+1<messages.size())selected.add(i+1);
        }
        if(count==0)throw new IllegalArgumentException("这个成员没有可关联的记录，请重新选择。");
        var result=Store.JSON.createArrayNode();
        for(int i:selected) {
            var m=messages.get(i);var key=conversationId+"/"+memberId+"/"+m.path("id").asText();
            var speaker=memberId.equals(m.path("memberId").asText())?"them":m.path("isMe").asBoolean()?"me":"context";
            var text=m.path("text").asText();if(speaker.equals("context"))text=m.path("memberName").asText(m.path("memberId").asText())+"："+text;
            var material=result.addObject().put("id","M-"+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))).put("text",text)
                .put("speaker",speaker).put("label",label+(speaker.equals("context")?" · 上下文":" · "+m.path("memberName").asText(memberId)))
                .put("at",m.path("at").asText()).put("sourceKey",key);
            if(RanchData.validMessageTime(m.path("spokenAt").asText()))material.put("spokenAt",m.path("spokenAt").asText());
        }
        return result;
    }
}
