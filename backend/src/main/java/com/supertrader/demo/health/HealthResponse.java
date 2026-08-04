package com.supertrader.demo.health;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Health response. Reports backend liveness, the AgentScope real-initialization
 * state, and version info. Carries NO credential of any kind.
 * SuperTrader-Demo has no trading-system connection, so there are no
 * broker / front-address fields.
 */
public record HealthResponse(
        String status,                                  // UP | DOWN
        @JsonProperty("agentscopeInitialized") boolean agentscopeInitialized,
        @JsonProperty("agentscopeDetail") String agentscopeDetail,
        @JsonProperty("backendVersion") String backendVersion,
        @JsonProperty("agentscopeVersion") String agentscopeVersion,
        @JsonProperty("modelConfigured") boolean modelConfigured) {
}
