package com.supertrader.demo.agentdemo;

import com.supertrader.demo.agentdemo.DemoSensitiveContentGuard.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1 tests for the Demo sensitive-content guard.
 *
 * <p>The guard runs BEFORE the message is persisted or sent to DeepSeek. It
 * rejects empty / oversized messages ({@code INVALID_TURN_CONTENT}) and
 * credential-shaped content ({@code SENSITIVE_CONTENT_REJECTED}). The guard's
 * error message must NEVER echo the offending raw text.
 */
class DemoSensitiveContentGuardTest {

    private final DemoSensitiveContentGuard guard = new DemoSensitiveContentGuard();

    @Test
    void rejectsNullAndBlank() {
        Verdict v1 = guard.check(null);
        assertFalse(v1.accepted());
        assertEquals(DemoSensitiveContentGuard.CODE_INVALID_TURN_CONTENT, v1.code());
        Verdict v2 = guard.check("   ");
        assertFalse(v2.accepted());
        assertEquals(DemoSensitiveContentGuard.CODE_INVALID_TURN_CONTENT, v2.code());
    }

    @Test
    void rejectsOver4000Characters() {
        StringBuilder sb = new StringBuilder();
        sb.append("a".repeat(4001));
        Verdict v = guard.check(sb.toString());
        assertFalse(v.accepted());
        assertEquals(DemoSensitiveContentGuard.CODE_INVALID_TURN_CONTENT, v.code());
        // 4000 is allowed (boundary).
        assertTrue(guard.check("a".repeat(4000)).accepted());
    }

    @Test
    void rejectsPasswordAssignment() {
        Verdict v = guard.check("我的 password=Aa123456 请登记");
        assertFalse(v.accepted());
        assertEquals(DemoSensitiveContentGuard.CODE_SENSITIVE_CONTENT_REJECTED, v.code());
        // Message must NOT echo the raw secret.
        assertFalse(v.message().contains("Aa123456"));
    }

    @Test
    void rejectsAuthCodeAndInvestorIdAndBrokerId() {
        assertFalse(guard.check("authCode=12345678").accepted());
        assertFalse(guard.check("investorId=00012345").accepted());
        assertFalse(guard.check("brokerId=9999 please").accepted());
        assertFalse(guard.check("BrokerID: 9999").accepted());
    }

    @Test
    void rejectsDeepSeekApiKeyShape() {
        Verdict v = guard.check("DEEPSEEK_API_KEY=sk-abcdef123456");
        assertFalse(v.accepted());
        assertEquals(DemoSensitiveContentGuard.CODE_SENSITIVE_CONTENT_REJECTED, v.code());
        assertFalse(v.message().contains("sk-abcdef"));
    }

    @Test
    void rejectsCommonSecretTokenShapes() {
        assertFalse(guard.check("token=eyJhbGci.payload.sig").accepted());
        assertFalse(guard.check("api_key=sk-test").accepted());
        assertFalse(guard.check("secret=s3cr3t").accepted());
    }

    @Test
    void allowsPlainStrategyDescription() {
        Verdict v = guard.check("黄金5日线上穿20日线买入，止损3%");
        assertTrue(v.accepted());
        assertNull(v.code());
    }

    @Test
    void allowsResearchAndNormalQuestions() {
        assertTrue(guard.check("为什么黄金上涨？").accepted());
        assertTrue(guard.check("解释一下均线策略").accepted());
        assertTrue(guard.check("你好").accepted());
    }

    @Test
    void allowsHarmlessNumbersThatAreNotCredentials() {
        // "止损3%" and "9999家期货公司" are NOT credential assignments and
        // must NOT be rejected by over-eager shape matching.
        assertTrue(guard.check("止损3%，止盈5%").accepted());
        assertTrue(guard.check("目前共有 9999 个合约").accepted());
    }

    @Test
    void rejectsWithoutEchoingRawContentForAnySensitiveMatch() {
        String raw = "account password=hunter2 used at login";
        Verdict v = guard.check(raw);
        assertFalse(v.accepted());
        assertFalse(v.message().contains("hunter2"));
        assertFalse(v.message().contains(raw));
    }
}
