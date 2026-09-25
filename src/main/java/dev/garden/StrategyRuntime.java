package dev.garden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.*;
import dev.dsh.contract.llm.ModelRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import reactor.core.Exceptions;
import reactor.core.publisher.*;

/** Bounded direct strategy calls; the analyzer alone owns business persistence. */
final class StrategyRuntime {
    static final class OutputLimitExceeded extends IllegalStateException {
        OutputLimitExceeded(){super("攻略输出超过上限");}
    }
    @FunctionalInterface interface RevisionCheck {void check() throws Exception;}
    private final ModelRegistry models;
    private final Duration timeout;
    StrategyRuntime(ModelRegistry models){this(models,Duration.ofSeconds(110));}
    StrategyRuntime(ModelRegistry models,Duration timeout){
        this.models=models;this.timeout=Objects.requireNonNull(timeout);
        if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("攻略时限必须为正数");
    }
    Run newRun(BooleanSupplier externallyCancelled){return new Run(externallyCancelled);}

    final class Run implements AutoCloseable {
        private final BooleanSupplier external;
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private final Sinks.Empty<Void> stop=Sinks.empty();
        private volatile boolean begun;
        private long deadline;
        Run(BooleanSupplier external){this.external=Objects.requireNonNull(external);}
        synchronized void begin(){
            if(begun)throw new IllegalStateException("攻略已开始");
            deadline=System.nanoTime()+timeout.toNanos();begun=true;
        }
        void cancel(){if(cancelled.compareAndSet(false,true))stop.tryEmitEmpty();}
        void check() throws TimeoutException {
            if(cancelled.get()||external.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("攻略已停止");
            if(!begun)throw new IllegalStateException("攻略尚未开始");
            if(System.nanoTime()>=deadline)throw new TimeoutException("攻略超过总时限");
        }
        private void checkUnchecked(){try{check();}catch(TimeoutException e){throw Exceptions.propagate(e);}}
        private Duration remaining() throws TimeoutException {check();return Duration.ofNanos(Math.max(1,deadline-System.nanoTime()));}
        private boolean stopped(){return cancelled.get()||external.getAsBoolean()||Thread.currentThread().isInterrupted()||(begun&&System.nanoTime()>=deadline);}
        private <T>T await(Mono<T> task,Duration limit) throws TimeoutException {
            try{return task.timeout(limit).block();}
            catch(RuntimeException error){
                var cause=Exceptions.unwrap(error);
                if(cause instanceof TimeoutException deadlineError)throw deadlineError;
                throw error;
            }
        }
        ObjectNode generate(ObjectNode input,ArrayNode passages,ProfileRuntime.Progress progress,RevisionCheck revisionCheck) throws Exception {
            check();if(models==null)throw new IllegalStateException("攻略模型未装配");
            String repair="";
            for(int attempt=1;attempt<=2;attempt++){
                check();revisionCheck.check();check();
                progress.update("preparing",attempt,"正在准备攻略模型调用");check();
                var stage=models.prepareCall(Map.<String,Object>of("provider","deepseek","model",Analyzer.MODEL,"reasoningEffort","off","maxTokens",6000),this::stopped);
                var available=remaining();var prepareLimit=available.compareTo(Duration.ofSeconds(15))<0?available:Duration.ofSeconds(15);
                // The contract has no PreparedCall.dispose. Release our wait without pretending to
                // cancel provider preparation; a late completion never dispatches its stream.
                var call=await(Mono.fromFuture(stage.toCompletableFuture(),true).takeUntilOther(stop.asMono()),prepareLimit);
                check();if(call==null)throw new CancellationException("攻略准备已停止");
                var request=new LinkedHashMap<String,Object>(call.config());request.put("signal",(BooleanSupplier)this::stopped);
                request.put("messages",List.of(message("system",RanchAnalyzer.COMMON+STRATEGY+repair),message("user",input.toString())));
                var output=new Output();int step=attempt;
                var text=await(Flux.defer(()->{
                    checkUnchecked();progress.update("reasoning",step,"正在生成相处攻略");checkUnchecked();
                    return call.stream(request);
                }).takeUntilOther(stop.asMono()).doOnNext(chunk->{checkUnchecked();output.accept(chunk);})
                    .then(Mono.fromCallable(()->{check();return output.finishedText();})),remaining());
                check();progress.update("validating",attempt,"正在核对攻略结构与引用");check();
                ObjectNode result;
                try {
                    var raw=text.trim().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
                    var parsed=Store.JSON.readTree(raw);
                    if(!(parsed instanceof ObjectNode object))throw new IllegalArgumentException("invalid root");
                    RanchData.validateStrategy(object,input,passages);result=object;
                }catch(JsonProcessingException|IllegalArgumentException invalid){
                    if(attempt==2)throw invalid;
                    repair="\n上一次结构或引用校验失败。严格遵守完整JSON结构，只引用输入真实ID，攻略必须包含目标本人的依据。";
                    continue;
                }
                check();return result;
            }
            throw new IllegalStateException("攻略校验失败");
        }
        @Override public void close(){cancel();}
    }
    private static final class Output {
        private final StringBuilder text=new StringBuilder();
        private int events;
        private boolean finished;
        void accept(Map<String,Object> chunk){
            if(++events>20000)throw new OutputLimitExceeded();
            if(finished)throw new IllegalStateException("攻略终态之后仍有输出");
            if("text-delta".equals(chunk.get("type"))){
                if(!(chunk.get("text") instanceof String delta))throw new IllegalStateException("攻略文本块无效");
                if(delta.length()>50000-text.length())throw new OutputLimitExceeded();text.append(delta);
            }
            if("finish".equals(chunk.get("type"))){
                if(!(chunk.get("reason") instanceof Map<?,?> reason)||!"stop".equals(reason.get("kind")))throw new IllegalStateException("攻略模型未正常结束");
                finished=true;
            }
        }
        String finishedText(){if(!finished)throw new IllegalStateException("攻略输出不完整");return text.toString();}
    }
    private static Map<String,Object> message(String role,String text){return Map.of("role",role,"content",List.of(Map.of("type","text","text",text)));}
 private static final String STRATEGY="""
  根据目标goalType(friendship认识朋友/romance发展亲密关系)、goal、双方真实材料和situation生成可调整的攻略。若自己的信息不足，明确缺口且给不假设用户偏好的中性做法。self材料和自述可辅助，但evidenceIds至少包含目标本人的材料ID。
  提供2至5个具体且自然的步骤、节奏与停下的信号。当前situation有对方来信或想主动聊的话，reply提供一条可编辑的自然回复；无具体情境可留空。不把发展亲密关系解释为已经互有好感。why是面向用户的简短依据说明，不是内部推理。
  reply中每句关于“我”的习惯和经历必须由self或situation明确支持，不能借用对方的时间、地点、体验来创造共同点。只有“喜欢散步”的资料，不能编成“我也常在下班后沿河拍照”。缺少共同经历时用提问、表达真实兴趣或提出可选计划，不编写迎合对方的自述。
  全文涉及任何人的性别或称谓都须有明确原文依据；依据不足时用姓名、“你”、“对方”或“本人”，不按名字或关系猜测他/她。
  reply中的相对日期和时间承诺仅能来自当前situation或明确的当下确认；旧材料尤其是日期未知材料中的“这周”“上次”等不能直接搬成当前承诺，应改成“我先核对自己的时间，确认后再约”等中性表述。当前situation明确说的“下周”等时间须保留，不因旧材料日期未知而删去。
  knowledgeIds只列真正帮助建议的片段ID，不相关则空数组。evidenceIds只列行为资料ID及自述ID；不能放书籍ID。
  材料spokenAt才是原来源确证的发言日期；没有则日期未知，不能由录入顺序推断同日、频率或持续时间。旧画像的时间概括不替代原文日期，不把相对日期锚定为当前日期。
  严格输出：{"overview":"目标与现状","steps":[{"title":"行动","detail":"怎么做及边界"}],"reply":"候选回复或空字符串","why":"依据和局限","evidenceIds":["材料ID"],"knowledgeIds":["书摘ID"]}
  """;
}
