package dev.ailiao.expert;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import com.sun.net.httpserver.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.ailiao.expert.MemoryPostgresSocketFactory.*;

/** Real JDBC + real transaction boundary, deterministic transport faults, no external services. */
class ProductionConnectionRecoveryTest {
    ProductionDatabase db(){return new ProductionDatabase("jdbc:postgresql://unused.invalid/fixture?socketFactory="+MemoryPostgresSocketFactory.class.getName()+"&gssEncMode=disable&sslmode=prefer&preferQueryMode=simple&assumeMinServerVersion=17","fixture",null,"fixture");}
    @AfterEach void cleanup(){Thread.interrupted();}
    @Test void healthyConnectionExecutesAndCommitsOnce()throws Exception {
        reset(Fault.NONE);AtomicInteger calls=new AtomicInteger();
        String result=db().transaction(c->{calls.incrementAndGet();c.createStatement().execute("INSERT INTO audit VALUES (1)");return "saved";});
        assertEquals("saved",result);assertEquals(1,calls.get());assertEquals(1,opened.size());
        assertEquals(1,opened.get(0).queries.stream().filter(q->q.startsWith("INSERT")).count());assertTrue(opened.get(0).queries.contains("COMMIT"));assertTrue(opened.get(0).closed);
    }
    @Test void transientHandshakeFailureRecoversBeforeExecutingBusinessWork()throws Exception {
        reset(Fault.SSL_TIMEOUT,Fault.NONE);AtomicInteger calls=new AtomicInteger();
        String result=db().transaction(c->{calls.incrementAndGet();c.createStatement().execute("INSERT INTO audit VALUES (1)");return "saved";});
        assertEquals("saved",result);assertEquals(1,calls.get());assertEquals(2,opened.size());
        assertTrue(opened.get(0).queries.isEmpty());assertEquals(1,opened.get(1).queries.stream().filter(q->q.startsWith("INSERT")).count());assertTrue(opened.stream().allMatch(s->s.closed));
    }
    @Test void persistentHandshakeFailureIsBoundedAndNeverExecutesBusinessWork(){
        reset(Fault.SSL_TIMEOUT,Fault.SSL_TIMEOUT,Fault.NONE);AtomicInteger calls=new AtomicInteger();
        SQLException error=assertThrows(SQLException.class,()->db().read(c->{calls.incrementAndGet();return "unexpected";}));
        assertEquals("08001",error.getSQLState());assertEquals(0,calls.get());assertEquals(2,opened.size());assertTrue(opened.stream().allMatch(s->s.closed));
    }
    @Test void certificateFailuresDoNotRetryOrDowngrade(){
        reset(Fault.TLS_FAILURE,Fault.NONE);assertThrows(SQLException.class,()->db().read(c->"unexpected"));assertEquals(1,opened.size());assertTrue(opened.get(0).closed);
    }
    @Test void authenticationFailureDoesNotRetry(){
        reset(Fault.AUTH_FAILURE,Fault.NONE);SQLException e=assertThrows(SQLException.class,()->db().read(c->"unexpected"));assertEquals("28P01",e.getSQLState());assertEquals(1,opened.size());assertTrue(opened.get(0).closed);
    }
    @Test void interruptedCallerDoesNotOpenAnotherConnection(){
        reset(Fault.SSL_TIMEOUT,Fault.NONE);Thread.currentThread().interrupt();
        assertThrows(SQLException.class,()->db().read(c->"unexpected"));
        assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,opened.size());assertTrue(opened.get(0).closed);
    }
    @Test void businessFailureIsNotReplayed(){
        reset(Fault.NONE);AtomicInteger calls=new AtomicInteger();
        SQLException original=new SQLException("fixture business connection failure","08006");
        assertSame(original,assertThrows(SQLException.class,()->db().transaction(c->{calls.incrementAndGet();c.createStatement().execute("INSERT INTO audit VALUES (1)");throw original;})));
        assertEquals(1,calls.get());assertEquals(1,opened.size());assertTrue(opened.get(0).queries.contains("ROLLBACK"));assertTrue(opened.get(0).closed);
    }
    @Test void uncertainCommitIsNotReplayed(){
        reset(Fault.COMMIT_TIMEOUT,Fault.NONE);AtomicInteger calls=new AtomicInteger();
        assertThrows(SQLException.class,()->db().transaction(c->{calls.incrementAndGet();c.createStatement().execute("INSERT INTO audit VALUES (1)");return "saved";}));
        assertEquals(1,calls.get());assertEquals(1,opened.size());assertEquals(1,opened.get(0).queries.stream().filter(q->q.startsWith("INSERT")).count());assertTrue(opened.get(0).closed);
    }
    @Test void exhaustedConnectionAttemptsReturnUnavailableInsteadOfInternalError()throws Exception {
        reset(Fault.SSL_TIMEOUT,Fault.SSL_TIMEOUT);
        try(var service=new ProductionService(db(),null,null,null,Path.of("."))){
            var exchange=new MemoryExchange();assertTrue(new ProductionHttp(service).handle(exchange));
            assertEquals(503,exchange.status);assertEquals("DATABASE_UNAVAILABLE",Json.parse(exchange.body.toString(java.nio.charset.StandardCharsets.UTF_8)).path("error").path("code").asText());
            assertEquals(2,opened.size());assertTrue(opened.stream().allMatch(s->s.queries.isEmpty()&&s.closed));
        }
    }
    static final class MemoryExchange extends HttpExchange {
        final Headers headers=new Headers();final ByteArrayOutputStream body=new ByteArrayOutputStream();int status;
        public Headers getRequestHeaders(){return new Headers();}public Headers getResponseHeaders(){return headers;}
        public URI getRequestURI(){return URI.create("/api/expert-production/v1/builds/00000000-0000-0000-0000-000000000001/snapshot");}
        public String getRequestMethod(){return "GET";}public HttpContext getHttpContext(){return null;}public void close(){}
        public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return body;}
        public void sendResponseHeaders(int code,long length){status=code;}public int getResponseCode(){return status;}
        public InetSocketAddress getRemoteAddress(){return null;}public InetSocketAddress getLocalAddress(){return null;}
        public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return null;}
        public void setAttribute(String key,Object value){}public void setStreams(InputStream in,OutputStream out){}
        public HttpPrincipal getPrincipal(){return null;}
    }
}
