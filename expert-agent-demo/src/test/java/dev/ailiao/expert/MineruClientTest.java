package dev.ailiao.expert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MineruClientTest {
    @TempDir Path dir;
    @Test void sendsCredentialsOnlyToApiAndUploadsExactOriginal() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        AtomicReference<String> apiAuth=new AtomicReference<>(), blobAuth=new AtomicReference<>(), request=new AtomicReference<>();
        AtomicReference<byte[]> uploaded=new AtomicReference<>(); AtomicInteger calls=new AtomicInteger();
        server.createContext("/api/v4/file-urls/batch",e->{ calls.incrementAndGet();apiAuth.set(e.getRequestHeaders().getFirst("Authorization"));request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response=("{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\""+origin+"/upload?signature=private\"]}}").getBytes();e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close(); });
        server.createContext("/upload",e->{blobAuth.set(e.getRequestHeaders().getFirst("Authorization"));uploaded.set(e.getRequestBody().readAllBytes());e.sendResponseHeaders(200,-1);e.close();});server.start();
        try {
            var client=new MineruClient(URI.create(origin),()->"test-secret");var job=new Jobs.Job("import", "ocr");
            var ticket=client.requestUpload("book.pdf","import-id",job);
            byte[] original="%PDF-1.7 exact-original".getBytes();Path input=dir.resolve("book.pdf");Files.write(input,original);
            client.upload(ticket.uploadUrl(),input,job);
            assertEquals("batch-1",ticket.batchId());assertEquals("Bearer test-secret",apiAuth.get());assertNull(blobAuth.get());assertArrayEquals(original,uploaded.get());assertEquals(1,calls.get());
            var body=Json.parse(request.get());assertEquals("vlm",body.path("model_version").asText());assertTrue(body.path("files").get(0).path("is_ocr").asBoolean());assertFalse(body.path("files").get(0).has("page_ranges"));
        } finally {server.stop(0);}
    }
    @Test void businessFailureNeverLeaksProviderEchoOrCredentials() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v4/file-urls/batch",e->{byte[] response="{\"code\":\"A0202\",\"msg\":\"secret-token https://host/x?signature=secret\"}".getBytes();e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});server.start();
        try {var client=new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"secret-token");Exception ex=assertThrows(Exception.class,()->client.requestUpload("book.pdf","id",new Jobs.Job("import","ocr")));assertFalse(ex.getMessage().contains("secret"));assertTrue(ex.getMessage().contains("Token"));}finally{server.stop(0);}
    }
}
