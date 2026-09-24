package dev.garden;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
class ChatChoicesTest {
 @Test void bindsSelectionToTheServerList() throws Exception {
  var cache=new ChatChoices();
  var input=(ObjectNode)Store.JSON.readTree("{\"status\":\"ok\",\"chats\":[{\"id\":\"row-123\",\"title\":\"群A\"}]}");
  var output=cache.register(input);String id=output.path("chats").get(0).path("choiceId").asText();
  assertFalse(id.isBlank());var command=cache.request(id);
  assertEquals("select",command.get("action"));assertEquals("row-123",command.get("chatId"));assertEquals("群A",command.get("chatTitle"));
  assertThrows(IllegalArgumentException.class,()->cache.request("row-123"));
 }
 @Test void omitsAmbiguousSameTitleChats() throws Exception {
  var cache=new ChatChoices();var input=(ObjectNode)Store.JSON.readTree("{\"status\":\"ok\",\"chats\":[{\"id\":\"a\",\"title\":\"同名\"},{\"id\":\"b\",\"title\":\"同名\"},{\"id\":\"c\",\"title\":\"唯一\"}]}");
  var output=cache.register(input);assertEquals(1,output.path("chats").size());assertEquals("唯一",output.path("chats").get(0).path("title").asText());
 }
}
