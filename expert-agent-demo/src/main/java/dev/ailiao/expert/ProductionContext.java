package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** DB保留全文；模型只获得有覆盖说明的工作窗口。摘要预览与完整方法明确区分。 */
final class ProductionContext {
    static ObjectNode interpretation(ObjectNode full,ArrayNode sourceChunks){
        ObjectNode out=full.deepCopy(),snapshot=(ObjectNode)out.path("snapshot");JsonNode old=snapshot.deepCopy();
        ArrayNode messages=Json.array();String replied=full.path("message").path("replyToMessageId").asText(),waiting=old.path("waitingQuestionMessageId").asText();
        JsonNode allMessages=old.path("messages");for(int i=0;i<allMessages.size();i++){JsonNode m=allMessages.get(i);boolean referenced=m.path("id").asText().equals(replied)||m.path("id").asText().equals(waiting);
            if(i>=allMessages.size()-12||referenced){ObjectNode view=Json.object().put("id",m.path("id").asText()).put("role",m.path("role").asText()).put("content",referenced?m.path("content").asText():preview(m.path("content").asText(),1600));if(m.has("presentation"))view.set("presentation",m.path("presentation"));messages.add(view);}}
        snapshot.set("messages",messages);ArrayNode artifacts=Json.array();
        for(JsonNode a:old.path("artifacts")){ObjectNode v=Json.object();for(String key:List.of("id","kind","agentRole","logicalKey","dependencyState"))v.set(key,a.path(key));JsonNode r=a.path("currentRevision");ObjectNode rv=Json.object();for(String key:List.of("id","revisionNo","generationStatus","reviewStatus","contentSha256"))rv.set(key,r.path(key));rv.set("sources",r.path("sources"));ObjectNode body=Json.object();for(String key:List.of("name","summary","responsibility"))body.put(key,preview(r.path("body").path(key).asText(),600));rv.set("body",body);v.set("currentRevision",rv);v.put("contextIsSummary",true);artifacts.add(v);}
        snapshot.set("artifacts",artifacts);snapshot.remove("summary");if(snapshot.path("currentArtifact") instanceof ObjectNode current)current.remove("revisions");ArrayNode jobs=Json.array();for(JsonNode j:old.path("jobs"))if(Set.of("queued","running","failed").contains(j.path("status").asText())){ObjectNode view=Json.object();for(String key:List.of("id","kind","status","phase","error"))view.set(key,j.path(key));jobs.add(view);}snapshot.set("jobs",jobs);
        ArrayNode clarifications=Json.array();for(JsonNode q:old.path("clarifications"))if("open".equals(q.path("status").asText()))clarifications.add(q);snapshot.set("clarifications",clarifications);
        Set<String> preferred=new HashSet<>();old.path("currentArtifact").path("currentRevision").path("sources").forEach(n->preferred.add(n.path("chunkId").asText()));
        ArrayNode sources=Json.array();List<JsonNode> ranked=new ArrayList<>();sourceChunks.forEach(ranked::add);ranked.sort(Comparator.comparingInt(n->preferred.contains(n.path("id").asText())?0:1));int chars=0;
        for(JsonNode chunk:ranked){int size=chunk.toString().length();if(chars+size<=18000){sources.add(chunk);chars+=size;}}
        out.set("sources",sources);out.set("contextCoverage",Json.object().put("totalSourceChunks",sourceChunks.size()).put("selectedSourceChunks",sources.size()).put("totalMessages",allMessages.size()).put("includedMessages",messages.size()));
        if(out.toString().length()>90000)throw new IllegalArgumentException("当前对话工作窗口过长，请聚焦一个成果提问");return out;
    }
    static ObjectNode generation(ObjectNode full) {
        ObjectNode out=full.deepCopy();ArrayNode units=Json.array(),methods=Json.array(),sources=Json.array(),approved=Json.array();
        Set<String> desired=new LinkedHashSet<>();full.path("plan").path("methodTitles").forEach(n->desired.add(n.asText()));
        List<List<JsonNode>> methodGroups=new ArrayList<>();int methodCount=0,unitNo=0;
        boolean bookSummary="book_summary".equals(full.path("kind").asText());
        for(JsonNode unit:full.path("learningUnits")){
            ObjectNode index=Json.object().put("batchNo",unitNo++).put("summary",preview(unit.path("summary").asText(),1200)).put("summaryIsPreview",unit.path("summary").asText().length()>1200).put("methodCount",unit.path("methods").size());
            ArrayNode titles=Json.array();int indexChars=0;List<JsonNode> group=new ArrayList<>();methodGroups.add(group);
            for(JsonNode method:unit.path("methods")){methodCount++;String title=method.path("title").asText();if(indexChars<1800){titles.add(preview(title,120));indexChars+=Math.min(120,title.length());}group.add(method);}
            index.set("methodTitles",titles);index.put("methodIndexIsPartial",titles.size()<unit.path("methods").size());
            if(unit.path("methods").isEmpty()){String reason=unit.path("coverage").path("noMethodReason").asText();index.put("noMethodReason",preview(reason,1200)).put("noMethodReasonIsPreview",reason.length()>1200).put("processedSourceCount",unit.path("coverage").path("processedSourceIds").size());}
            if(unit.path("coverage").hasNonNull("limitations"))index.set("limitations",unit.path("coverage").path("limitations").deepCopy());
            units.add(index);
        }
        List<JsonNode> candidates=roundRobin(methodGroups);
        // Reserve a fair first pass for each batch; methods always remain complete.
        Set<JsonNode> chosen=new LinkedHashSet<>();int methodChars=2;
        for(JsonNode method:candidates)if(desired.contains(method.path("title").asText())&&methodChars+method.toString().length()+1<=18000){chosen.add(method);methodChars+=method.toString().length()+1;}
        if(bookSummary&&!methodGroups.isEmpty())for(List<JsonNode> group:methodGroups){
            int share=(18000-2)/methodGroups.size();
            for(JsonNode method:group)if(!chosen.contains(method)&&method.toString().length()+1<=share&&methodChars+method.toString().length()+1<=18000){chosen.add(method);methodChars+=method.toString().length()+1;break;}
        }
        for(JsonNode method:candidates)if(!chosen.contains(method)&&methodChars+method.toString().length()+1<=18000){chosen.add(method);methodChars+=method.toString().length()+1;}
        methods.addAll(chosen);
        Map<String,JsonNode> chunks=new LinkedHashMap<>();full.path("sources").forEach(n->chunks.put(n.path("id").asText(),n));
        List<List<String>> sourceGroups=new ArrayList<>();int batch=0;
        for(JsonNode unit:full.path("learningUnits")){
            Set<String> ids=new LinkedHashSet<>();
            List<JsonNode> group=methodGroups.get(batch++);
            for(JsonNode method:methods)if(group.contains(method))method.path("sourceIds").forEach(n->ids.add(n.asText()));
            unit.path("sourceIds").forEach(n->ids.add(n.asText()));
            unit.path("coverage").path("processedSourceIds").forEach(n->ids.add(n.asText()));
            ids.removeIf(id->!chunks.containsKey(id));sourceGroups.add(new ArrayList<>(ids));
        }
        Set<String> rankedIds=new LinkedHashSet<>();
        if(!bookSummary)for(JsonNode method:methods)method.path("sourceIds").forEach(n->rankedIds.add(n.asText()));
        rankedIds.addAll(roundRobin(sourceGroups));
        full.path("previousRevision").path("sources").forEach(n->rankedIds.add(n.path("chunkId").asText()));
        rankedIds.addAll(chunks.keySet());
        Set<String> included=new LinkedHashSet<>();int sourceChars=2;
        if(bookSummary){
            Set<String> representatives=new LinkedHashSet<>();for(List<String> group:sourceGroups)if(!group.isEmpty())representatives.add(group.get(0));
            int remaining=representatives.size();
            for(String id:representatives){
                JsonNode excerpt=sourceExcerpt(chunks.get(id),(18000-sourceChars)/remaining-- -1);
                if(excerpt!=null){sources.add(excerpt);included.add(id);sourceChars+=excerpt.toString().length()+1;}
            }
        }
        for(String id:rankedIds){JsonNode chunk=chunks.get(id);if(chunk!=null&&!included.contains(id)&&sourceChars+chunk.toString().length()+1<=18000){sources.add(chunk);included.add(id);sourceChars+=chunk.toString().length()+1;}}
        if(sources.isEmpty()&&!chunks.isEmpty())throw new IllegalArgumentException("单个原文片段超出模型工作窗口");
        for(JsonNode a:full.path("approvedArtifacts")){
            ObjectNode view=Json.object();for(String key:List.of("kind","logical_key","agent_role","current_revision_id","review_status"))if(a.has(key))view.set(key,a.get(key));
            JsonNode body=a.path("body");
            if(Set.of("book_summary","keyword_rule","qa_example").contains(a.path("kind").asText())){view.set("body",body.deepCopy());view.put("contextIsSummary",false);approved.add(view);continue;}
            ObjectNode info=Json.object();for(String key:List.of("name","responsibility","summary"))if(body.has(key))info.put(key,preview(body.path(key).asText(),600));
            info.put("boundariesPreview",preview(body.path("boundaries").toString(),800));view.set("body",info);view.put("contextIsSummary",true);approved.add(view);
        }
        ObjectNode coverage=Json.object().put("totalSourceChunks",full.path("sources").size()).put("selectedSourceChunks",sources.size()).put("totalLearningUnits",units.size()).put("totalMethods",methodCount).put("fullMethodsInContext",methods.size()).put("allOriginalTextIncluded",sources.size()==full.path("sources").size()&&sources.findValues("textIsPreview").stream().noneMatch(JsonNode::asBoolean));
        coverage.set("selectedSourceIds",Json.MAPPER.valueToTree(sources.findValuesAsText("id")));coverage.put("note","所有学习批次均提供摘要及方法索引，代表已保存的学习覆盖；只有relevantMethods是本轮完整方法，预算不足时不保证每批均有完整方法。sources仅是本轮可逐字引用的证据窗口，textIsPreview=true表示该片段仅含逐字前缀。不得因章节未进入引用窗口就声称该章节未学习，也不得声称已读取窗口外原文；真实学习缺口以各批次limitations及摘要中的限制为准，limitations完整保留且不因方法或原文未入窗而省略。");
        out.set("learningUnits",units);out.set("relevantMethods",methods);out.set("sources",sources);out.set("approvedArtifacts",approved);out.set("contextCoverage",coverage);
        if(out.toString().length()>90000)throw new IllegalArgumentException("当前修订及摘要超过模型工作窗口，请缩小单次修改范围");
        return out;
    }
    private static <T> List<T> roundRobin(List<List<T>> groups){
        List<T> out=new ArrayList<>();int largest=groups.stream().mapToInt(List::size).max().orElse(0);
        for(int i=0;i<largest;i++)for(List<T> group:groups)if(i<group.size())out.add(group.get(i));return out;
    }
    /** Only summary evidence is excerpted; quotes remain exact substrings of the stored source. */
    private static JsonNode sourceExcerpt(JsonNode source,int budget){
        if(source.toString().length()<=budget)return source;
        String text=source.path("text").asText();ObjectNode out=source.deepCopy();out.put("textIsPreview",true).put("originalTextChars",text.length());
        int low=0,high=text.codePointCount(0,text.length());
        while(low<high){int mid=(low+high+1)/2;out.put("text",text.substring(0,text.offsetByCodePoints(0,mid)));if(out.toString().length()<=budget)low=mid;else high=mid-1;}
        if(low==0)return null;out.put("text",text.substring(0,text.offsetByCodePoints(0,low)));return out;
    }
    static ArrayNode previousUnits(ArrayNode all){ArrayNode out=Json.array();int fullChars=0;for(int i=0;i<all.size();i++){JsonNode u=all.get(i);if(i>=all.size()-2&&fullChars+u.toString().length()<=24000){out.add(u);fullChars+=u.toString().length();}else out.add(Json.object().put("summary",preview(u.path("summary").asText(),1000)).put("contextIsSummary",true));}return out;}
    static String preview(String text,int max){return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max))+"（上下文预览，全文已入库）";}
}
