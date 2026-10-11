package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** 模型只选择原文句段；文本和码点位置始终由服务端生成。 */
final class ProductionCitations {
    record Span(String id, String text, int start, int end) {}

    static List<Span> spans(String text) {
        List<Span> result=new ArrayList<>();int startChar=0,startPoint=0,point=0;
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);i+=Character.charCount(cp);point++;
            boolean boundary=(point-startPoint>=40&&"。！？!?；;\n".indexOf(cp)>=0)||point-startPoint>=240||i==text.length();
            if(boundary&&!text.substring(startChar,i).isBlank()) {
                result.add(new Span("s"+(result.size()+1),text.substring(startChar,i),startPoint,point));startChar=i;startPoint=point;
            }
        }
        // 末尾空白仍属于原文，不丢字符，也不生成可单独引用的空白句段。
        if(startChar<text.length()&&!result.isEmpty()) {
            Span last=result.remove(result.size()-1);
            result.add(new Span(last.id(),last.text()+text.substring(startChar),last.start(),point));
        }
        return result;
    }

    static ObjectNode modelInput(ObjectNode window) {
        ObjectNode out=window.deepCopy();
        for(JsonNode source:out.path("sources")) {
            ObjectNode item=(ObjectNode)source;ArrayNode options=Json.array();
            for(Span span:spans(item.path("text").asText()))options.add(Json.object().put("id",span.id()).put("text",span.text()));
            item.remove("text");item.set("citationSpans",options);
        }
        if(out.toString().length()>90000)throw new IllegalArgumentException("当前修订及原文句段超过模型工作窗口，请缩小单次修改范围");
        return out;
    }

    static List<int[]> selectedRanges(JsonNode source,String original) {
        if(source.has("quote")||source.has("startOffset")||source.has("endOffset"))throw new IllegalArgumentException("来源句段选择不能混用引文或偏移");
        ArrayNode ids=ProductionRules.array(source,"spanIds",true);List<Span> spans=spans(original);SortedSet<Integer> selected=new TreeSet<>();
        for(JsonNode id:ids) {
            int found=-1;
            if(id.isTextual())for(int i=0;i<spans.size();i++)if(spans.get(i).id().equals(id.asText())){found=i;break;}
            if(found<0)throw new IllegalArgumentException("来源句段不在指定原文片段内");
            if(!selected.add(found))throw new IllegalArgumentException("来源句段不能重复选择");
        }
        List<int[]> ranges=new ArrayList<>();int previous=-2;
        for(int index:selected){Span span=spans.get(index);if(index==previous+1)ranges.get(ranges.size()-1)[1]=span.end();else ranges.add(new int[]{span.start(),span.end()});previous=index;}
        return ranges;
    }
}
