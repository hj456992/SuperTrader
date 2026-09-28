package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExpertRuntimeTest {
    @Test void allDelegatedFailuresCannotCountAsSuccessfulTrial() {
        var answer=Json.object().put("answer","无法取得资料");answer.set("sourceIds",Json.array());
        assertThrows(IllegalArgumentException.class,()->ExpertRuntime.validateTrialResult(answer,Set.of(),2,Json.array()));
    }
    @Test void greetingCanCompleteWithoutQualifyingForActivation() {
        var answer=Json.object().put("answer","你好");answer.set("sourceIds",Json.array());
        assertFalse(ExpertRuntime.validateTrialResult(answer,Set.of(),0,Json.array()));
    }
    @Test void onlyGroundedSuccessfulDelegationQualifies() {
        var answer=Json.object().put("answer","建议");answer.set("sourceIds",Json.array().add("read-source"));
        var reports=Json.array().add(Json.object().put("analysis","子专家分析"));
        assertTrue(ExpertRuntime.validateTrialResult(answer,Set.of("read-source"),1,reports));
        assertThrows(IllegalArgumentException.class,()->ExpertRuntime.validateTrialResult(answer,Set.of(),1,reports));
        answer.set("sourceIds",Json.array());
        assertThrows(IllegalArgumentException.class,()->ExpertRuntime.validateTrialResult(answer,Set.of("read-source"),1,reports));
    }
}
