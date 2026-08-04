package com.supertrader.demo.taskcenter;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: every tool name registered into the AgentScope toolkit MUST
 * match the OpenAI / DeepSeek function-name regex {@code ^[a-zA-Z0-9_-]+$}.
 * Dot-namespaced identifiers (e.g. {@code strategy.read}) are REJECTED by the
 * upstream API with HTTP 400, which silently forces every model call to fall
 * back to the deterministic planner. This test guarantees the tool names sent
 * to the model are always valid.
 */
class TaskCenterToolNamePatternTest {

    private static final Pattern OPENAI_FN_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

    @Test
    void allRegisteredToolNamesMatchOpenAIFunctionNamePattern() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TaskCenterTools(new KnowledgeService("/unused")));
        for (String name : toolkit.getToolNames()) {
            assertTrue(OPENAI_FN_NAME.matcher(name).matches(),
                    "tool name '" + name + "' must match ^[a-zA-Z0-9_-]+$ "
                            + "(dot-namespaced ids are rejected by DeepSeek/OpenAI)");
        }
    }
}
