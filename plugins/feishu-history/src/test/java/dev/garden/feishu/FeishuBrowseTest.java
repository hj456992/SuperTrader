package dev.garden.feishu;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class FeishuBrowseTest extends FeishuCaptureTest {
    static final String LIST = "{\"status\":\"ok\",\"platform\":\"feishu\",\"scope\":\"loaded_chats\",\"chats\":[{\"id\":\"chat_1-A\",\"title\":\"测试群\"}],\"detail\":\"当前已加载会话\"}";

    @SuppressWarnings("unchecked")
    Function<Map<String,Object>,Map<String,Object>> browse(FeishuCapture capture) {
        return (Function<Map<String,Object>,Map<String,Object>>)assertInstanceOf(Function.class, capture);
    }

    @Test void listAppendsOnlyListArgumentAndReturnsWhitelistedChats() throws Exception {
        String script = "import sys\nassert sys.argv[1:] == ['list']\n" + printJson(LIST.replace("\"title\":\"测试群\"", "\"title\":\"测试群\",\"private\":\"discard\""));
        try (var capture = new FeishuCapture(config(script, 5))) {
            var result = browse(capture).apply(Map.of("action", "list"));
            assertEquals("ok", result.get("status"));
            assertEquals("loaded_chats", result.get("scope"));
            assertEquals(List.of(Map.of("id", "chat_1-A", "title", "测试群")), result.get("chats"));
        }
    }

    @Test void selectPassesTitleAsOneLiteralArgumentAndCaptureRemainsNoArg() throws Exception {
        String title = "测试群 ' ; $(echo hello) `echo world`";
        String script = "import sys,json\n" +
            "data=json.loads('''" + SUCCESS + "''')\n" +
            "if sys.argv[1:]:\n" +
            "    assert sys.argv[1:] == ['select','chat_1-A',\"测试群 ' ; $(echo hello) `echo world`\"]\n" +
            "    data['chatTitle']=sys.argv[3]\n" +
            "print(json.dumps(data))\n";
        try (var capture = new FeishuCapture(config(script, 5))) {
            var result = browse(capture).apply(Map.of("action", "select", "chatId", "chat_1-A", "chatTitle", title));
            assertEquals("ok", result.get("status"));
            assertEquals(title, result.get("chatTitle"));
            assertEquals("current_view", result.get("scope"));
            assertEquals("测试群", capture.get().get("chatTitle"));
        }
    }

    @Test void invalidRequestsNeverStartCollector() throws Exception {
        List<Map<String,Object>> invalid = new ArrayList<>(List.of(Map.of(), Map.of("action", "send"),
            Map.of("action", "list", "extra", true), Map.of("action", "select"),
            Map.of("action", "select", "chatId", "../escape", "chatTitle", "测试群"),
            Map.of("action", "select", "chatId", "x".repeat(161), "chatTitle", "测试群"),
            Map.of("action", "select", "chatId", "valid", "chatTitle", " "),
            Map.of("action", "select", "chatId", "valid", "chatTitle", "😀".repeat(51))));
        invalid.add(null);
        try (var capture = new FeishuCapture(config("import pathlib\npathlib.Path(__file__).with_name('started').touch()\n" + printJson(LIST), 5))) {
            for (var request : invalid) assertEquals("error", browse(capture).apply(request).get("status"));
            assertFalse(Files.exists(directory.resolve("started")));
        }
    }

    @Test void listRejectsDuplicateIdsMalformedEntriesAndOversize() throws Exception {
        for (String chats : List.of("[{\"id\":\"a\",\"title\":\"A\"},{\"id\":\"a\",\"title\":\"B\"}]",
                "[{\"id\":1,\"title\":\"A\"}]", "[{\"id\":\"a\",\"title\":\" \"}]", "[42]",
                "[" + String.join(",", java.util.stream.IntStream.range(0,201).mapToObj(i -> "{\"id\":\"a"+i+"\",\"title\":\"A\"}").toList()) + "]")) {
            String value = "{\"status\":\"ok\",\"platform\":\"feishu\",\"scope\":\"loaded_chats\",\"chats\":" + chats + ",\"detail\":\"已加载\"}";
            try (var capture = new FeishuCapture(config(printJson(value), 5))) {
                assertEquals("error", browse(capture).apply(Map.of("action", "list")).get("status"));
            }
        }
    }

    @Test void selectRejectsSnapshotOfDifferentChatAndWrongScope() throws Exception {
        for (String value : List.of(SUCCESS.replace("测试群", "其他群"), LIST)) {
            try (var capture = new FeishuCapture(config(printJson(value), 5))) {
                assertEquals("error", browse(capture).apply(Map.of("action", "select", "chatId", "a", "chatTitle", "测试群")).get("status"));
            }
        }
    }

    @Test void browseForwardsNotFoundAndAmbiguous() throws Exception {
        for (String status : List.of("not_found", "ambiguous")) {
            try (var capture = new FeishuCapture(config(printJson("{\"status\":\"" + status + "\",\"detail\":\"无法确定群聊\"}"), 5))) {
                assertEquals(Map.of("status",status,"detail","无法确定群聊"), browse(capture).apply(Map.of("action", "select", "chatId", "a", "chatTitle", "测试群")));
            }
        }
    }

    @Test void browseSharesCaptureLockAndCloseLifecycle() throws Exception {
        var capture = new FeishuCapture(config(sleeper(), 30));
        try {
            var function = browse(capture);
            var task = new FutureTask<>(() -> function.apply(Map.of("action", "list")));
            new Thread(task).start();
            List<Long> pids = awaitPids();
            assertEquals("busy", capture.get().get("status"));
            assertEquals("busy", function.apply(Map.of("action", "list")).get("status"));
            capture.close();
            assertEquals("error", task.get(3,TimeUnit.SECONDS).get("status"));
            assertDead(pids);
            Files.delete(directory.resolve("pids"));
            assertEquals("error", function.apply(Map.of("action", "list")).get("status"));
            assertFalse(Files.exists(directory.resolve("pids")));
        } finally { capture.close(); }
    }
}
