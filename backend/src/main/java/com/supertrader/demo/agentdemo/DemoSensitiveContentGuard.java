package com.supertrader.demo.agentdemo;

import java.util.regex.Pattern;

/**
 * The Agent Demo sensitive-content guard (Task 1).
 *
 * <p>Runs BEFORE a user message is persisted or sent to DeepSeek. It rejects:
 * <ul>
 *   <li>empty / blank / over-4000-character messages
 *       ({@link #CODE_INVALID_TURN_CONTENT});</li>
 *   <li>credential-shaped content — password / authCode / investorId / brokerId
 *       / DeepSeek key / token / secret assignments
 *       ({@link #CODE_SENSITIVE_CONTENT_REJECTED}).</li>
 * </ul>
 *
 * <p>The guard's error message NEVER echoes the offending raw text: a rejected
 * message reports only a generic reason. A plain strategy description (e.g.
 * "黄金5日线上穿20日线买入，止损3%") passes — "止损3%" is not a credential
 * assignment, so it is not matched.
 *
 * <p>This is a deterministic, offline, side-effect-free check.
 */
public final class DemoSensitiveContentGuard {

    /** Max user message length (matches the design's 4,000-char output budget). */
    public static final int MAX_CONTENT_LENGTH = 4000;

    public static final String CODE_INVALID_TURN_CONTENT = "INVALID_TURN_CONTENT";
    public static final String CODE_SENSITIVE_CONTENT_REJECTED = "SENSITIVE_CONTENT_REJECTED";

    /**
     * Credential assignment shapes. Matches {@code name=value} or
     * {@code name: value} for known sensitive field names, case-insensitive.
     * Mirrors the masking vocabulary in {@code ToolProxy.SENSITIVE_PAIR} plus
     * the explicit DeepSeek key name. The value must be non-empty and not just
     * punctuation so ordinary prose ("止损3%") is not swept in.
     */
    static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|authcode|auth_code|investorid|investor_id|"
                    + "brokerid|broker_id|appid|app_id|usercode|user_code|"
                    + "token|access_token|accesstoken|refresh_token|refreshtoken|"
                    + "secret|apikey|api_key|deepseek_api_key|deepseek-api-key|"
                    + "privatekey|private_key|credential|simnow_password|ctp_password)"
                    + "\\s*[=:]\\s*[A-Za-z0-9_\\-./+=]{3,}");

    /** The outcome of one content check. */
    public record Verdict(boolean accepted, String code, String message) {

        static Verdict ok() {
            return new Verdict(true, null, null);
        }

        static Verdict reject(String code) {
            return new Verdict(false, code, genericMessage(code));
        }
    }

    /**
     * Check one user message. Returns a {@link Verdict}; on rejection the
     * message field carries a generic, echo-free reason.
     */
    public Verdict check(String raw) {
        if (raw == null) {
            return Verdict.reject(CODE_INVALID_TURN_CONTENT);
        }
        String content = raw.trim();
        if (content.isEmpty()) {
            return Verdict.reject(CODE_INVALID_TURN_CONTENT);
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Verdict.reject(CODE_INVALID_TURN_CONTENT);
        }
        if (SENSITIVE_ASSIGNMENT.matcher(content).find()) {
            return Verdict.reject(CODE_SENSITIVE_CONTENT_REJECTED);
        }
        return Verdict.ok();
    }

    /** A generic, echo-free rejection reason for the given code. */
    private static String genericMessage(String code) {
        if (CODE_INVALID_TURN_CONTENT.equals(code)) {
            return "消息内容无效或过长（最多 " + MAX_CONTENT_LENGTH + " 字）。";
        }
        return "检测到疑似凭证或敏感信息，已拒绝处理。请轮换相关凭证，且不要在对话中粘贴。";
    }
}
