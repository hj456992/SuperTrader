package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRedisTest {
    @Test void unavailableRedisDropsCacheButNeverGrantsPaidQuota() throws Exception {
        var redis=new ProductionRedis("redis://127.0.0.1:1", "");
        assertTrue(redis.get("ep:test:missing").isEmpty());
        assertDoesNotThrow(()->redis.cache("ep:test:missing","x",Duration.ofSeconds(1)));
        assertThrows(Exception.class,()->redis.permit("test","unavailable",1,Duration.ofSeconds(2)));
    }
    @Test @EnabledIfEnvironmentVariable(named="EXPERT_REDIS_URL",matches=".+")
    void sharedQuotaAndImmutableCacheUseRealRedis() throws Exception {
        var redis=ProductionRedis.fromEnvironment();String identity=Json.id();
        assertTrue(redis.enabled());
        redis.cache("ep:test:"+identity,"immutable body",Duration.ofSeconds(30));
        assertEquals("immutable body",redis.get("ep:test:"+identity).orElseThrow());
        assertTrue(redis.permit("integration",identity,1,Duration.ofSeconds(10)));
        assertFalse(ProductionRedis.fromEnvironment().permit("integration",identity,1,Duration.ofSeconds(10)));
    }
}
