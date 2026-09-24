package dev.garden;

import dev.dsh.contract.llm.ModelRegistry;
import dev.dsh.kernel.api.*;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/** 花园后端插件：由 dsh-java 管理依赖和资源释放。 */
public final class GardenPlugin implements Plugin {
    /** @param context 底座插件作用域和依赖服务。 */
    @Override
    public Mono<Void> start(PluginContext context) {
        return Mono.fromRunnable(() -> {
            try {
                var store = new Store();
                var analyzer = new Analyzer(context.require(ModelRegistry.KEY), store);
                context.own(() -> {
                    analyzer.close();
                    return CompletableFuture.completedFuture(null);
                });
                var ranchStore = new RanchStore();
                var knowledge = new RanchKnowledge(ranchStore);
                var ranchAnalyzer = new RanchAnalyzer(context.require(ModelRegistry.KEY), ranchStore, knowledge, new ProfileRuntime(context));
                var liveSources = new RanchLiveSources(context.require(new ServiceKey<>("feishu.browse", java.util.function.Function.class)));
                context.own(() -> {liveSources.close(); return CompletableFuture.completedFuture(null);});
                var ranch = new RanchService(ranchStore, ranchAnalyzer, knowledge, new RanchSources(store,liveSources));
                context.own(() -> {
                    ranchAnalyzer.close();
                    return CompletableFuture.completedFuture(null);
                });
                var port = Integer.parseInt(System.getenv().getOrDefault("GARDEN_PORT", "48740"));
                var api = new HttpApi(port, Path.of(System.getenv("GARDEN_WEB")), new GardenService(store, analyzer, context.require(new ServiceKey<>("feishu.capture", java.util.function.Supplier.class)), context.require(new ServiceKey<>("feishu.browse", java.util.function.Function.class))), ranch);
                context.own(() -> {
                    api.close();
                    return CompletableFuture.completedFuture(null);
                });
                api.start();
                System.out.println("爱聊 Demo ready: http://127.0.0.1:" + port + "/");
            } catch (Exception error) {
                throw new IllegalStateException("花园插件启动失败，请检查独立数据库、端口和前端构建。", error);
            }
        });
    }
}
