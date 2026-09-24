package dev.garden.wechat;

import dev.dsh.kernel.api.Plugin;
import dev.dsh.kernel.api.PluginContext;
import dev.dsh.kernel.api.ServiceKey;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/** dsh-java 插件入口，无模型依赖，不自行创建底座或访问微信进程。 */
public final class WechatHistoryPlugin implements Plugin {
    public static final ServiceKey<Map> STATUS = new ServiceKey<>("wechat.history.status", Map.class);

    /** 使用本次实例配置并把全部运行时资源交给底座释放。 */
    @Override
    public Mono<Void> start(PluginContext context) {
        return Mono.fromRunnable(() -> {
            if (!(context.config() instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("wechat-history requires a configuration map");
            }
            HistoryWorker worker;
            try {
                worker = new HistoryWorker(values);
            } catch (Exception error) {
                throw new IllegalStateException("wechat-history configuration or private directory unavailable");
            }
            try {
                context.own(() -> {
                    worker.close();
                    return CompletableFuture.completedFuture(null);
                });
                context.provide(STATUS, worker.statusView());
                worker.start();
            } catch (RuntimeException error) {
                worker.close();
                throw error;
            }
        });
    }
}
