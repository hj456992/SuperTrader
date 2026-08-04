package com.supertrader.demo.health;

import com.supertrader.demo.agentscope.AgentScopeBootstrap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/health} — backend status and AgentScope real-init state.
 * Returns NO credential of any kind. SuperTrader-Demo does not connect to any
 * trading system, so there are no broker / front-address fields here.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final AgentScopeBootstrap agentscope;

    public HealthController(AgentScopeBootstrap agentscope) {
        this.agentscope = agentscope;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                agentscope.initialized(),
                agentscope.detail(),
                "1.0.0",
                agentscope.version(),
                agentscope.modelConfigured());
    }
}
