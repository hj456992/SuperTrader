package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 不依赖模型自称成功的边界校验；规范摘要不包含可变审核状态。 */
final class ProductionRules {
    /** 引文唯一逐字匹配后计算码点；兼容合法旧偏移，但不猜测、不纠正冲突值。 */
    static ObjectNode normalizeSources(ObjectNode raw,ArrayNode chunks){
        Map<String,JsonNode> byId=new HashMap<>();chunks.forEach(chunk->byId.put(chunk.path("id").asText(),chunk));
        ObjectNode out=raw.deepCopy();ArrayNode normalized=Json.array();
        for(JsonNode source:array(raw,"sources",true)){
            JsonNode chunk=byId.get(source.path("chunkId").asText());if(chunk==null)throw new IllegalArgumentException("来源片段不在本次原文窗口");
            String original=chunk.path("text").asText();int length=original.codePointCount(0,original.length()),start,end;
            if(source.has("quote")){
                if(!source.path("quote").isTextual()||source.path("quote").asText().isBlank())throw new IllegalArgumentException("来源引文必须为非空逐字文本");
                String quote=source.path("quote").asText();int found=original.indexOf(quote);
                if(found<0)throw new IllegalArgumentException("来源引文未在指定片段逐字匹配");
                if(original.indexOf(quote,found+1)>=0)throw new IllegalArgumentException("来源引文多处匹配，请提供更完整的唯一引文");
                int finish=found+quote.length();if(!codePointBoundary(original,found)||!codePointBoundary(original,finish))throw new IllegalArgumentException("来源引文切断Unicode码点");
                start=original.codePointCount(0,found);end=original.codePointCount(0,finish);
                if(source.has("startOffset")||source.has("endOffset"))if(offset(source,"startOffset")!=start||offset(source,"endOffset")!=end)throw new IllegalArgumentException("来源引文与声明偏移不一致");
            }else{start=offset(source,"startOffset");end=offset(source,"endOffset");}
            if(start<0||end<=start||end>length)throw new IllegalArgumentException("来源码点范围越界");
            normalized.add(Json.object().put("chunkId",source.path("chunkId").asText()).put("startOffset",start).put("endOffset",end).put("purpose",Json.required(source,"purpose",1000)));
        }
        out.set("sources",normalized);return out;
    }
    /** 章节缺省时仅依据本轮已验证引用的真实文档位置补齐，不改变引用。 */
    static ObjectNode normalizeChapters(String kind,ObjectNode raw,ArrayNode chunks){
        ObjectNode out=raw.deepCopy();if(!"agent".equals(kind)||!(out.path("body") instanceof ObjectNode body))return out;
        JsonNode chapters=body.path("chapters");
        if(!(chapters.isMissingNode()||chapters.isNull()||(chapters.isArray()&&chapters.isEmpty())))return out;
        ObjectNode verified=normalizeSources(out,chunks);Map<String,JsonNode> byId=new HashMap<>();chunks.forEach(c->byId.put(c.path("id").asText(),c));
        Set<String> labels=new LinkedHashSet<>();
        for(JsonNode source:verified.path("sources")){
            JsonNode chunk=byId.get(source.path("chunkId").asText());JsonNode version=chunk.path("documentVersionId"),page=chunk.path("pageNo");
            if(!version.isTextual()||version.asText().isBlank())throw new ValidationFailure("sources.documentVersionId",version,"已验证来源缺少文档版本，不能生成章节定位");
            if(!page.isIntegralNumber()||!page.canConvertToInt()||page.asInt()<1)throw new ValidationFailure("sources.pageNo",page,"已验证来源缺少有效页码，不能生成章节定位");
            JsonNode path=chunk.path("chapterPath");String chapter=path.isTextual()&&!path.asText().isBlank()?path.asText():"未识别章节";
            labels.add("文档版本 "+version.asText()+"："+chapter+"，第"+page.asInt()+"页");
        }
        ArrayNode locations=Json.array();labels.forEach(locations::add);body.set("chapters",locations);return out;
    }
    /** 只携带失败字段的类型/长度，不带模型值或资料正文。 */
    static final class ValidationFailure extends IllegalArgumentException {
        private final ObjectNode shape;
        ValidationFailure(String field,JsonNode value,String message){
            super(message);shape=Json.object().put("field",field).put("type",value.getNodeType().name()).put("length",value.isTextual()?value.asText().length():value.size());
            if(value.isArray()){Set<String> types=new TreeSet<>();value.forEach(v->types.add(v.getNodeType().name()));shape.set("elementTypes",Json.MAPPER.valueToTree(types));}
        }
        ObjectNode details(){return shape.deepCopy();}
    }
    private static String requiredField(JsonNode node,String key,int max){
        try{return Json.required(node,key,max);}catch(IllegalArgumentException invalid){throw new ValidationFailure(key,node.path(key),invalid.getMessage());}
    }
    private static int offset(JsonNode source,String key){JsonNode value=source.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException("来源偏移必须是整数码点位置");return value.intValue();}
    private static boolean codePointBoundary(String value,int offset){return offset==0||offset==value.length()||!(Character.isHighSurrogate(value.charAt(offset-1))&&Character.isLowSurrogate(value.charAt(offset)));}
    /** 诊断绝不包含chunk id、引文或原文，只含类型、长度和边界数字。 */
    static ArrayNode sourceShape(ObjectNode raw,ArrayNode chunks){
        Map<String,JsonNode> byId=new HashMap<>();chunks.forEach(chunk->byId.put(chunk.path("id").asText(),chunk));ArrayNode out=Json.array();int index=0;
        for(JsonNode source:raw.path("sources")){JsonNode chunk=byId.get(source.path("chunkId").asText());ObjectNode row=Json.object().put("index",index++).put("chunkExists",chunk!=null).put("quoteType",source.path("quote").getNodeType().name()).put("quoteLength",source.path("quote").asText("").codePointCount(0,source.path("quote").asText("").length()));
            if(chunk!=null){String text=chunk.path("text").asText();row.put("sourceCodePointLength",text.codePointCount(0,text.length()));}
            for(String field:List.of("startOffset","endOffset")){row.put(field+"Type",source.path(field).getNodeType().name());if(source.path(field).isIntegralNumber())row.set(field,source.path(field));}out.add(row);
        }return out;
    }
    /** 生产方法校验不继承旧Demo的2400字/12来源限制；总输出预算由ModelCalls控制。 */
    static ObjectNode normalizePrelearn(ObjectNode raw,ArrayNode passages){
        ObjectNode out=raw.deepCopy();text(out,"summary");Set<String> allowed=Knowledge.ids(passages);ArrayNode methods=array(out,"methods",false);
        if(methods.isEmpty()){
            JsonNode coverage=out.path("coverage");text(coverage,"noMethodReason");Set<String> processed=new HashSet<>();
            for(JsonNode ref:array(coverage,"processedSourceIds",true)){if(!ref.isTextual()||!allowed.contains(ref.asText()))throw new IllegalArgumentException("无方法批次覆盖来源无效");processed.add(ref.asText());}
            if(!processed.equals(allowed))throw new IllegalArgumentException("无方法批次仍须覆盖全部来源");
            for(JsonNode ref:array(out,"sourceIds",true))if(!ref.isTextual()||!allowed.contains(ref.asText()))throw new IllegalArgumentException("无方法批次引用本批以外的来源");
        }
        for(JsonNode value:methods){
            if(!(value instanceof ObjectNode method))throw new IllegalArgumentException("预学习方法必须为对象");
            for(String field:List.of("title","when","limits"))text(method,field);
            JsonNode steps=method.path("steps");
            if(steps.isArray()){
                if(steps.isEmpty())throw new IllegalArgumentException("steps有序文本数组不能为空");StringJoiner text=new StringJoiner("\n");int i=0;
                for(JsonNode step:steps){if(!step.isTextual()||step.asText().isBlank())throw new IllegalArgumentException("steps数组每项必须为非空文本");text.add((++i)+". "+step.asText());}
                method.set("stepsOriginal",steps.deepCopy());method.put("steps",text.toString());
            }else text(method,"steps");
            for(JsonNode ref:array(method,"sourceIds",true))if(!ref.isTextual()||!allowed.contains(ref.asText()))throw new IllegalArgumentException("预学习方法引用本批以外的来源");
        }
        return out;
    }
    private static void text(JsonNode node,String key){if(!node.path(key).isTextual()||node.path(key).asText().isBlank())throw new IllegalArgumentException(key+"必须为非空文本");}
    /** 只输出类型/长度，绝不记录模型正文、书籍文本或凭据。 */
    static ObjectNode prelearnShape(ObjectNode output){
        ObjectNode shape=Json.object().put("summaryType",output.path("summary").getNodeType().name()).put("summaryLength",output.path("summary").asText("").length());ArrayNode methods=Json.array();
        for(JsonNode method:output.path("methods")){ObjectNode fields=Json.object();for(String key:List.of("title","when","steps","limits","sourceIds")){JsonNode field=method.path(key);ObjectNode detail=Json.object().put("type",field.getNodeType().name()).put("length",field.isTextual()?field.asText().length():field.size());if(field.isArray()){Set<String> types=new TreeSet<>();field.forEach(item->types.add(item.getNodeType().name()));detail.set("elementTypes",Json.MAPPER.valueToTree(types));}fields.set(key,detail);}methods.add(fields);}shape.set("methods",methods);return shape;
    }
    static String hash(JsonNode value) throws Exception {return hashBytes(canonical(value).toString().getBytes(StandardCharsets.UTF_8));}
    static String hashBytes(byte[] value) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static JsonNode canonical(JsonNode n){
        if(n.isObject()){ObjectNode out=Json.object();TreeSet<String> keys=new TreeSet<>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(n.get(k))));return out;}
        if(n.isArray()){ArrayNode out=Json.array();n.forEach(v->out.add(canonical(v)));return out;}return n;
    }
    static String scope(String kind){return switch(kind){case "book_summary"->"summary.full";case "team_manifest"->"team.full";default->"artifact.full";};}
    /** 缺省改写只保留原话，不推断新的意图或授权。 */
    static void normalizeRewrite(ObjectNode intent,String original){
        JsonNode rewritten=intent.path("rewrittenText");
        if(rewritten.isMissingNode()||rewritten.isNull()||(rewritten.isTextual()&&rewritten.asText().isBlank())){
            intent.put("rewrittenText",original).put("rewritten",false);return;
        }
        if(!rewritten.isTextual())throw new IllegalArgumentException("rewrittenText必须为文本");
        Json.required(intent,"rewrittenText",16000);intent.put("rewritten",!original.equals(rewritten.asText()));
    }
    static boolean unambiguousIntent(JsonNode n){
        for(String flag:List.of("conditional","ambiguous","quoted"))if(!n.path(flag).isBoolean()||n.path(flag).booleanValue())return false;
        return true;
    }
    static boolean unconditionalApproval(JsonNode n){return "approve".equals(n.path("intent").asText())&&unambiguousIntent(n);}
    static ArrayNode array(JsonNode n,String key,boolean nonempty){if(!(n.path(key) instanceof ArrayNode a)||nonempty&&a.isEmpty())throw new ValidationFailure(key,n.path(key),"模型缺少有效 "+key);return a;}
    static void validateGenerated(String kind,ObjectNode out,ArrayNode chunks,ArrayNode specialists){
        if(!(out.path("body") instanceof ObjectNode body))throw new ValidationFailure("body",out.path("body"),"模型缺少完整body");
        Map<String,JsonNode> sources=new HashMap<>();chunks.forEach(c->sources.put(c.path("id").asText(),c));
        Set<String> spans=new HashSet<>();
        for(JsonNode source:array(out,"sources",true)){
            JsonNode chunk=sources.get(source.path("chunkId").asText());int start=source.path("startOffset").asInt(-1),end=source.path("endOffset").asInt(-1);
            if(chunk==null||start<0||end<=start||end>chunk.path("text").asText().codePointCount(0,chunk.path("text").asText().length()))throw new ValidationFailure("sources",out.path("sources"),"模型来源越界");
            requiredField(source,"purpose",1000);if(!spans.add(source.path("chunkId").asText()+":"+start+":"+end))throw new ValidationFailure("sources",out.path("sources"),"重复来源范围");
        }
        array(out,"clarifications",false);
        switch(kind){
            case "book_summary"->{
                requiredField(body,"title",500);requiredField(body,"summary",16000);array(body,"outline",true);array(body,"limitations",false);
                var plans=array(body,"specialists",true);if(plans.size()>6)throw new IllegalArgumentException("专业分工不得超过6位");Set<String> keys=new HashSet<>();
                for(JsonNode p:plans){String key=requiredField(p,"key",100);if(!keys.add(key))throw new IllegalArgumentException("重复专业分工");requiredField(p,"name",200);requiredField(p,"responsibility",3000);array(p,"methodTitles",true);}
            }
            case "agent"->{for(String field:List.of("name","description","summary","responsibility","model","overlapAnalysis"))requiredField(body,field,12000);for(String field:List.of("tools","capabilities","boundaries","chapters"))array(body,field,!field.equals("tools"));for(JsonNode chapter:body.path("chapters"))if(!chapter.isTextual()||chapter.asText().isBlank())throw new ValidationFailure("chapters",body.path("chapters"),"chapters必须为非空字符串列表");requiredField(out,"systemPrompt",30000);}
            case "keyword_rule"->{Set<String> expected=new HashSet<>();specialists.forEach(p->expected.add(p.path("key").asText()));Set<String> seen=new HashSet<>();for(JsonNode rule:array(body,"rules",true)){String key=requiredField(rule,"specialistKey",100);if(!expected.contains(key))throw new IllegalArgumentException("关键词指向未知专家");seen.add(key);array(rule,"keywords",true);}if(!seen.equals(expected))throw new IllegalArgumentException("关键词未覆盖全部专业专家");if(!"L3".equals(body.path("multiMatch").asText())||!"L2".equals(body.path("noMatch").asText()))throw new IllegalArgumentException("关键词路由约束错误");}
            case "qa_example"->{Set<String> expected=new HashSet<>();specialists.forEach(p->expected.add(p.path("key").asText()));Set<String> seen=new HashSet<>();for(JsonNode q:array(body,"examples",true)){String key=requiredField(q,"specialistKey",100);if(!expected.contains(key))throw new IllegalArgumentException("问答指向未知专家");seen.add(key);requiredField(q,"question",5000);requiredField(q,"answer",10000);for(JsonNode id:array(q,"sourceIds",true))if(!sources.containsKey(id.asText()))throw new IllegalArgumentException("问答来源越界");}if(!seen.equals(expected))throw new IllegalArgumentException("问答未覆盖全部专业专家");if(!"cosine".equals(body.path("metric").asText())||body.path("threshold").asDouble()!=0.90||!">".equals(body.path("comparison").asText())||!"L3".equals(body.path("multiMatch").asText())||!"L3".equals(body.path("noMatch").asText())||!"aliyun-bailian".equals(body.path("embeddingProvider").asText()))throw new IllegalArgumentException("语义路由约束错误");}
            case "team_manifest"->{requiredField(body,"summary",16000);array(body,"flow",true);array(body,"limitations",false);}
            default->throw new IllegalArgumentException("未知成果类型");
        }
    }
}
