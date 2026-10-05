package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** 只携带白名单诊断，不保存供应商原始异常消息、正文或凭据。 */
final class ProductionModelException extends IllegalArgumentException {
    private final String code,finishKind;
    private final int tokens,characters;
    ProductionModelException(String code,String finishKind,int tokens,int characters){
        super(description(code)+"（"+code+", finish="+safeFinish(finishKind)+"）");
        this.code=code;this.finishKind=safeFinish(finishKind);this.tokens=tokens;this.characters=characters;
    }
    ObjectNode details(){return Json.object().put("code",code).put("finishKind",finishKind).put("maxOutputTokens",tokens).put("maxOutputCharacters",characters).put("message",getMessage());}
    private static String safeFinish(String kind){return kind!=null&&Set.of("stop","max-tokens","length","error","tool-calls","content-filter","content_filter","cancelled","none").contains(kind)?kind:"unknown";}
    private static String description(String code){return switch(code){
        case "MODEL_OUTPUT_LIMIT"->"模型输出达到预算，未保存不完整结果";
        case "MODEL_STREAM_INCOMPLETE"->"模型流缺少完整结束标记";
        case "MODEL_JSON_INVALID"->"模型结束后未返回合法完整JSON";
        case "MODEL_TRANSPORT_FAILED"->"模型网络传输失败";
        case "MODEL_TIMEOUT"->"模型调用超时";
        case "MODEL_CONFIGURATION_INVALID"->"生产模型预算配置无效";
        case "MODEL_FINISH_ERROR"->"模型供应商返回失败终态";
        default->"模型调用未正常完成";
    };}
    static ProductionModelException classify(Exception error,int tokens,int characters){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof ProductionModelException safe)return safe;
            if(cause instanceof java.util.concurrent.TimeoutException)return new ProductionModelException("MODEL_TIMEOUT","none",tokens,characters);
            if(cause instanceof java.io.IOException)return new ProductionModelException("MODEL_TRANSPORT_FAILED","none",tokens,characters);
        }
        return new ProductionModelException("MODEL_CALL_FAILED","none",tokens,characters);
    }
}
