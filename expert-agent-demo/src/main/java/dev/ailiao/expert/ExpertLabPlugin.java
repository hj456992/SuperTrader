package dev.ailiao.expert;

import dev.dsh.kernel.api.*;
import dev.dsh.contract.llm.ModelRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/** 专家实验室的 DSH 插件入口；宿主只负责合同、依赖和生命周期。 */
public final class ExpertLabPlugin implements Plugin {
    /** 装配业务能力并把资源释放交给底座。@param context 插件作用域、配置与依赖 */
    @Override
    public Mono<Void> start(PluginContext context) {
        return Mono.fromRunnable(() -> {
            try {
                Map<?, ?> config = context.config() instanceof Map<?, ?> values ? values : Map.of();
                Path root = Path.of(value(config, "dataDir", ".local/expert-lab"));
                LabStore store = new LabStore(root);
                ModelCalls models = new ModelCalls(context.require(ModelRegistry.KEY), value(config, "provider", "deepseek"), value(config, "model", "deepseek-v4-flash"));
                Jobs jobs = new Jobs(); context.own(() -> { jobs.close(); return CompletableFuture.completedFuture(null); });
                PdfImport pdf = new PdfImport(value(config, "python", "python3"), Path.of(value(config, "extractor", "extract_pdf.py")), root.resolve("tmp"));
                MineruImport imports = new MineruImport(root, store, jobs, new MineruClient(), pdf);
                ExpertBuilder builder = new ExpertBuilder(models, store);
                ExpertRuntime runtime = new ExpertRuntime(context, store);
                int port = Integer.parseInt(value(config, "port", "48760"));
                LabHttp http = new LabHttp(port, store, imports, builder, runtime, jobs);
                context.own(() -> { http.close(); return CompletableFuture.completedFuture(null); });
                http.start(); System.out.println("Expert Lab plugin ready: http://127.0.0.1:" + port + "/");
            } catch (Exception error) { throw new IllegalStateException("专家实验室插件无法启动，请检查依赖、目录和端口", error); }
        });
    }
    /** 读取可替换插件配置。@param config 配置 @param key 名称 @param fallback 默认值 */
    private static String value(Map<?, ?> config, String key, String fallback) { Object value = config.get(key); return value == null ? fallback : value.toString(); }
}
