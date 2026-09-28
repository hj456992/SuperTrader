package dev.ailiao.expert;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JsonTest {
    @Test void acceptsCompleteObjectWithOptionalFence() throws Exception { assertEquals("回答",Json.parse("```json\n{\"answer\":\"回答\"}\n```").path("answer").asText()); }
    @Test void rejectsTruncationTrailingTextAndMultipleObjects() {
        for(String value:new String[]{"{\"answer\":", "{\"answer\":\"ok\"} 解释", "{} {}", "[]"})assertThrows(Exception.class,()->Json.parse(value));
    }
}
