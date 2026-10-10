package dev.ailiao.expert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import javax.net.ssl.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class MineruClientTest {
    @TempDir Path dir;
    private static final String PRIVATE="secret-token https://host/upload?signature=secret-body";
    static Stream<Object[]> transportErrors(){return Stream.of(
        new Object[]{new HttpConnectTimeoutException(PRIVATE),"MINERU_CONNECT_TIMEOUT"},
        new Object[]{new HttpTimeoutException(PRIVATE),"MINERU_REQUEST_TIMEOUT"},
        new Object[]{new TimeoutException(PRIVATE),"MINERU_REQUEST_TIMEOUT"},
        new Object[]{new SSLHandshakeException(PRIVATE),"MINERU_TLS_FAILURE"},
        new Object[]{new ConnectException(PRIVATE),"MINERU_CONNECTION_FAILED"},
        new Object[]{new IOException(PRIVATE),"MINERU_NETWORK_IO"},
        new Object[]{new IllegalStateException(PRIVATE),"MINERU_TRANSPORT_FAILURE"});}
    @ParameterizedTest @MethodSource("transportErrors")
    void transportFailuresKeepSafeClassificationWithoutRetainingSecrets(Throwable cause,String code)throws Exception{
        MineruClient.Failure failure=sendFailure(CompletableFuture.failedFuture(cause),30);
        ObjectNode diagnostics=diagnostics(failure);
        assertEquals(code,diagnostics.path("code").asText());assertEquals("文件上传",diagnostics.path("phase").asText());
        assertEquals(List.of("java.util.concurrent.ExecutionException",cause.getClass().getName()),Json.MAPPER.convertValue(diagnostics.path("causeTypes"),List.class));
        assertTrue(failure.retryable);assertFalse(diagnostics.has("httpStatus"));assertSafe(failure,diagnostics);
    }
    @Test void localRequestDeadlineIsClassifiedAndCancelsOutstandingRequest()throws Exception{
        CompletableFuture<HttpResponse<Object>> pending=new CompletableFuture<>();
        MineruClient.Failure failure=sendFailure(pending,0);ObjectNode diagnostic=diagnostics(failure);
        assertEquals("MINERU_REQUEST_TIMEOUT",diagnostic.path("code").asText());
        assertEquals(List.of("java.util.concurrent.TimeoutException"),Json.MAPPER.convertValue(diagnostic.path("causeTypes"),List.class));
        assertTrue(pending.isCancelled());assertSafe(failure,diagnostic);
    }
    @Test void causeCyclesAndExcessiveDepthAreBoundedWithoutKeepingThrowableObjects()throws Exception{
        RuntimeException first=new RuntimeException(PRIVATE),second=new RuntimeException(PRIVATE);first.initCause(second);second.initCause(first);
        MineruClient.Failure cycle=assertTimeoutPreemptively(Duration.ofSeconds(2),()->sendFailure(CompletableFuture.failedFuture(first),30));
        assertEquals(3,cycle.causeTypes.size());assertEquals("MINERU_TRANSPORT_FAILURE",cycle.code);assertSafe(cycle,cycle.diagnostics());
        Throwable deep=new SSLHandshakeException(PRIVATE);for(int i=0;i<30;i++)deep=new RuntimeException(PRIVATE,deep);
        MineruClient.Failure bounded=sendFailure(CompletableFuture.failedFuture(deep),30);
        assertEquals(12,bounded.causeTypes.size());assertSafe(bounded,bounded.diagnostics());
        assertThrows(UnsupportedOperationException.class,()->bounded.causeTypes.add(PRIVATE));
        bounded.diagnostics().withArray("causeTypes").removeAll();assertEquals(12,bounded.diagnostics().path("causeTypes").size());
    }
    @Test void nestedTlsFailureWinsOverIoWrapperAndKnownFailureKeepsNonRetryablePolicy()throws Exception{
        MineruClient.Failure tls=sendFailure(CompletableFuture.failedFuture(new IOException(PRIVATE,new SSLHandshakeException(PRIVATE))),30);
        assertEquals("MINERU_TLS_FAILURE",tls.code);assertSafe(tls,tls.diagnostics());
        MineruClient.Failure known=new MineruClient.Failure("结果超过下载体积上限",false);
        MineruClient.Failure limit=sendFailure(CompletableFuture.failedFuture(new IOException(PRIVATE,known)),30);
        assertFalse(limit.retryable);assertEquals(known.getMessage(),limit.getMessage());assertEquals("文件上传",limit.phase);assertSafe(limit,limit.diagnostics());
    }
    @Test void httpFailureIncludesStatusWithoutProviderResponseOrRequestSecrets()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v4/file-urls/batch",e->{byte[] response=PRIVATE.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(503,response.length);e.getResponseBody().write(response);e.close();});server.start();
        try{
            var client=new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"secret-token");
            MineruClient.Failure failure=assertThrows(MineruClient.Failure.class,()->client.requestUpload("book.pdf","id",new Jobs.Job("import","ocr")));
            ObjectNode diagnostic=failure.diagnostics();assertEquals("MINERU_HTTP_ERROR",diagnostic.path("code").asText());
            assertEquals(503,diagnostic.path("httpStatus").asInt());assertEquals("API 请求",diagnostic.path("phase").asText());
            assertTrue(failure.retryable);assertTrue(failure.getMessage().contains("HTTP 503"));assertSafe(failure,diagnostic);
        }finally{server.stop(0);}
    }
    private static ObjectNode diagnostics(MineruClient.Failure failure)throws Exception{
        return failure.diagnostics();
    }
    private static void assertSafe(MineruClient.Failure failure,ObjectNode diagnostic){
        assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
        StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));
        for(String text:List.of(failure.getMessage(),diagnostic.toString(),trace.toString()))for(String secret:List.of("secret-token","signature=","secret-body","https://host"))assertFalse(text.contains(secret),text);
    }
    private static MineruClient.Failure sendFailure(CompletableFuture<HttpResponse<Object>> response,int seconds)throws Exception{
        var method=MineruClient.class.getDeclaredMethod("send",HttpClient.class,HttpRequest.class,HttpResponse.BodyHandler.class,Jobs.Job.class,int.class,String.class);method.setAccessible(true);
        InvocationTargetException thrown=assertThrows(InvocationTargetException.class,()->method.invoke(null,new FailedClient(response),HttpRequest.newBuilder(URI.create("https://host/upload?signature=secret-body")).build(),HttpResponse.BodyHandlers.discarding(),new Jobs.Job("import","ocr"),seconds,"文件上传"));
        return assertInstanceOf(MineruClient.Failure.class,thrown.getCause());
    }
    /** Deterministic transport boundary: no network or supplier traffic. */
    private static final class FailedClient extends HttpClient {
        private final CompletableFuture<HttpResponse<Object>> response;
        FailedClient(CompletableFuture<HttpResponse<Object>> response){this.response=response;}
        @SuppressWarnings("unchecked") public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){return (CompletableFuture<HttpResponse<T>>)(CompletableFuture<?>)response;}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){throw new AssertionError();}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new AssertionError();}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.empty();}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new AssertionError();}public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
    }
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
