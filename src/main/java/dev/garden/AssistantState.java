package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Conversation-scoped member identity, grounded analysis and durable profile history. */
final class AssistantState {
    private AssistantState() {}

    static ObjectNode describe(ObjectNode person) {
        var settings = person.path("assistantSettings");
        var configured = settings.path("conversationType").asText("auto");
        var selfId = settings.path("selfId").asText("");
        var namedSenders = new LinkedHashSet<String>();
        for(var message:person.path("messages")) {
            var sender=message.path("sender").asText("").strip();
            if(!sender.isEmpty() && !Set.of("发言人未显示","未知").contains(sender)) namedSenders.add(sender);
        }
        var inferredType=namedSenders.size()>1?"group":"direct";
        var type="auto".equals(configured)?inferredType:configured;
        var otherSenders=namedSenders.stream().filter(n->!("sender:"+n).equals(selfId)).toList();
        var members = new LinkedHashMap<String, ObjectNode>();
        boolean named = false;
        for (var message : person.path("messages")) {
            var sender = message.path("sender").asText("").strip();
            String id, name;
            if (!sender.isEmpty()) {
                named = true;
                if (sender.equals("发言人未显示") || sender.equals("未知")) continue;
                id = "sender:" + sender; name = sender;
            } else if ("them".equals(message.path("role").asText())) {
                if("direct".equals(type) && otherSenders.size()==1) {
                    name=otherSenders.get(0);id="sender:"+name;
                } else {id = "counterpart"; name = person.path("name").asText("对方");}
            } else continue;
            var member = members.computeIfAbsent(id, key -> {
                var node = Store.JSON.createObjectNode().put("id", key).put("name", name);
                node.putArray("messageIds"); return node;
            });
            ((ArrayNode)member.path("messageIds")).add(message.path("id").asText());
        }
        // A role-only "them" is not a specific member in a manually declared group.
        if ("group".equals(type)) members.remove("counterpart");
        boolean roleIdentity = !"feishu_desktop".equals(person.path("source").asText());
        var result = Store.JSON.createObjectNode().put("type",type).put("inferred","auto".equals(configured))
            .put("selfId",selfId).put("roleIdentity",roleIdentity)
            .put("identityKnown",(roleIdentity && "me".equals(selfId)) || (named ? members.containsKey(selfId) : "direct".equals(type)))
            .put("namedSource",named).put("recentCount",Math.min(20,person.path("messages").size()));
        var list = result.putArray("members"); members.values().forEach(list::add);
        var focuses = result.putArray("focusIds");
        if ("direct".equals(type)) {
            var others = members.keySet().stream().filter(id -> !id.equals(selfId)).toList();
            if (others.size() == 1) focuses.add(others.get(0));
        } else {
            for (var id : settings.path("focusIds")) {
                if (members.containsKey(id.asText()) && !selfId.equals(id.asText())) focuses.add(id.asText());
            }
        }
        return result;
    }

    static void configure(ObjectNode person, JsonNode body) {
        var type = body.path("conversationType").asText("");
        if (!Set.of("auto","group","direct").contains(type) || !body.path("selfId").isTextual()
            || !body.path("focusIds").isArray() || body.path("focusIds").size() > 5)
            throw new IllegalArgumentException("请选择会话类型，最多同时关注5人。");
        var next = Store.JSON.createObjectNode().put("conversationType",type).put("selfId",body.path("selfId").asText());
        next.set("focusIds",body.path("focusIds").deepCopy());
        var copy = person.deepCopy(); copy.set("assistantSettings", next);
        var context = describe(copy);
        var valid = new HashSet<String>();context.path("members").forEach(m->valid.add(m.path("id").asText()));
        var self = next.path("selfId").asText();
        if (!self.isEmpty() && !valid.contains(self) && !(self.equals("me") && context.path("roleIdentity").asBoolean()))
            throw new IllegalArgumentException("身份不在当前会话成员中。");
        var seen = new HashSet<String>();
        for (var id : next.path("focusIds")) {
            if (!id.isTextual() || !valid.contains(id.asText()) || self.equals(id.asText()) || !seen.add(id.asText()))
                throw new IllegalArgumentException("请选择当前会话中不同于自己的成员。");
        }
        boolean changedType = !describe(person).path("type").equals(context.path("type"));
        person.set("assistantSettings",next);
        invalidate(person,changedType);
    }

    static ObjectNode modelContext(ObjectNode person) {
        var context=describe(person);
        var recent=context.putArray("recentMessageIds");
        var messages=person.path("messages");
        for (int i=Math.max(0,messages.size()-20);i<messages.size();i++) recent.add(messages.get(i).path("id").asText());
        return context;
    }

    static void validate(ObjectNode result, ObjectNode person) {
        var context=modelContext(person);
        var allowed=new HashSet<String>();person.path("messages").forEach(m->allowed.add(m.path("id").asText()));
        var recent=new HashSet<String>();context.path("recentMessageIds").forEach(id->recent.add(id.asText()));
        statement(result.path("summary"),recent,null);
        var response=result.path("response");
        if (!response.isObject() || !Set.of("needed","optional","none","unknown").contains(response.path("status").asText()))
            fail("invalid response status");
        string(response,"reason",1200,false);string(response,"reply",1500,true);
        evidence(response,recent,null,"unknown".equals(response.path("status").asText()));
        var expected=new LinkedHashMap<String,Set<String>>();
        for(var id:context.path("focusIds")) {
            var owned=new HashSet<String>();
            for(var m:context.path("members")) if(m.path("id").asText().equals(id.asText())) m.path("messageIds").forEach(mid->owned.add(mid.asText()));
            expected.put(id.asText(),owned);
        }
        if(!result.path("profiles").isArray() || result.path("profiles").size()!=expected.size()) fail("invalid profiles count");
        var seen=new HashSet<String>();
        for(var profile:result.path("profiles")) {
            var id=profile.path("memberId").asText();
            if(!expected.containsKey(id) || !seen.add(id)) fail("invalid profile member");
            statement(profile.path("overview"),allowed,expected.get(id));
            if(!profile.path("traits").isArray() || profile.path("traits").size()>5) fail("invalid traits");
            for(var trait:profile.path("traits")) {
                statement(trait,allowed,expected.get(id));
                if(!Set.of("explicit","inferred").contains(trait.path("kind").asText())) fail("invalid trait kind");
                ((ObjectNode)trait).retain(List.of("text","kind","evidenceIds"));
            }
            string(profile,"uncertainty",1200,false);string(profile,"change",1200,false);
            ((ObjectNode)profile.path("overview")).retain(List.of("text","evidenceIds"));
            ((ObjectNode)profile).retain(List.of("memberId","overview","traits","uncertainty","change"));
        }
        var decision=(ObjectNode)response;
        if(!context.path("identityKnown").asBoolean()) {
            decision.put("status","unknown").put("reason","尚未确认你在这段会话中的身份，暂时无法判断是否有人在等你回复。");
        }
        if(Set.of("none","unknown").contains(decision.path("status").asText())) decision.put("reply","");
        decision.retain(List.of("status","reason","evidenceIds","reply"));
        ((ObjectNode)result.path("summary")).retain(List.of("text","evidenceIds"));
        result.retain(List.of("summary","response","profiles"));
        result.put("version",2).put("reply",decision.path("reply").asText()).put("conversationType",context.path("type").asText());
    }

    static void record(ObjectNode person,ObjectNode result) {
        person.set("analysis",result.deepCopy());
        var profiles=person.withObject("/profiles");
        for(var entry:result.path("profiles")) {
            var id=entry.path("memberId").asText();
            var saved=profiles.path(id).isObject()?(ObjectNode)profiles.get(id):profiles.putObject(id);
            var snapshot=entry.deepCopy();
            ((ObjectNode)snapshot).put("analysisId",result.path("id").asText()).put("createdAt",result.path("createdAt").asText());
            saved.set("latest",snapshot);
            saved.put("stale",false);
            if(!saved.path("history").isArray()) saved.putArray("history");
            var history=(ArrayNode)saved.get("history");history.add(snapshot.deepCopy());
            while(history.size()>30) history.remove(0);
        }
    }

    static void invalidate(ObjectNode person,boolean revoke) {
        person.putNull("analysis");
        if(revoke) {person.putObject("profiles");person.remove("lastAnalyzedIds");}
        else person.path("profiles").forEach(p->{if(p instanceof ObjectNode o)o.put("stale",true);});
    }

    private static void statement(JsonNode node,Set<String> allowed,Set<String> owned) {
        string(node,"text",1500,false);evidence(node,allowed,owned,false);
    }
    private static void string(JsonNode node,String key,int max,boolean blank) {
        if(!node.path(key).isTextual() || node.path(key).asText().length()>max || (!blank&&node.path(key).asText().isBlank())) fail("invalid "+key);
    }
    private static void evidence(JsonNode node,Set<String> allowed,Set<String> owned,boolean empty) {
        var ids=node.path("evidenceIds");
        if(!ids.isArray() || (!empty&&ids.isEmpty()) || ids.size()>12) fail("invalid evidence");
        boolean own=owned==null;
        for(var id:ids) {
            if(!id.isTextual() || !allowed.contains(id.asText())) fail("unknown evidence");
            if(owned!=null&&owned.contains(id.asText())) own=true;
        }
        if(!own) fail("profile needs member's own evidence");
    }
    private static void fail(String code) {throw new IllegalArgumentException(code);}
}
