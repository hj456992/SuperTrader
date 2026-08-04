package com.supertrader.demo.agentdemo;

/**
 * A typed Demo API exception carrying a stable error code (design §10.2).
 * Mapped to an HTTP status + the uniform error envelope by
 * {@link AgentDemoErrorHandler}.
 */
public class AgentDemoApiException extends RuntimeException {

    private final String code;

    public AgentDemoApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
