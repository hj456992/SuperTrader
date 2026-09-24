package dev.garden.feishu;

import dev.dsh.kernel.api.Plugin;
import dev.dsh.kernel.api.PluginContext;
import dev.dsh.kernel.api.ServiceKey;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Host owns the collector lifetime; callers request an explicit current-view snapshot. */
public final class FeishuHistoryPlugin implements Plugin {
    public static final ServiceKey<Supplier> CAPTURE = new ServiceKey<>("feishu.capture",Supplier.class);
    public static final ServiceKey<Function> BROWSE = new ServiceKey<>("feishu.browse",Function.class);

    @Override public Mono<Void> start(PluginContext context) {
        return Mono.fromRunnable(() -> {
            if (!(context.config() instanceof Map<?,?> config)) throw new IllegalArgumentException("feishu-history requires a configuration map");
            FeishuCapture capture = new FeishuCapture(config);
            try {
                context.own(() -> {
                    capture.close();
                    return CompletableFuture.completedFuture(null);
                });
                context.provide(CAPTURE,capture);
                context.provide(BROWSE,capture);
            } catch (RuntimeException failure) {
                capture.close();
                throw failure;
            }
        });
    }
}
