package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ProductionDiagnosticsTest {
    @Test void sqlDiagnosticKeepsStateAndOwnedFramesWithoutSqlOrExceptionBody()throws Exception{
        var error=new java.sql.SQLException("PRIVATE_SQL_WITH_PARAMS","40001",7);
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("dev.ailiao.expert.ProductionDatabase","run","ProductionDatabase.java",63),new StackTraceElement("outside.Driver","unsafe","Driver.java",1)});
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();PrintStream saved=System.err;
        try{System.setErr(new PrintStream(bytes,true,StandardCharsets.UTF_8));Class.forName("dev.ailiao.expert.ProductionDiagnostics").getDeclaredMethod("log",String.class,Exception.class).invoke(null,"worker",error);}finally{System.setErr(saved);}
        String text=bytes.toString(StandardCharsets.UTF_8);assertTrue(text.contains("SQLException"));assertTrue(text.contains("SQLState=40001"));assertTrue(text.contains("vendorCode=7"));assertTrue(text.contains("ProductionDatabase.run"));assertFalse(text.contains("PRIVATE_SQL_WITH_PARAMS"));assertFalse(text.contains("outside.Driver"));
    }
    @Test void timestampAndNestedConnectionCauseAreSafeAndDiagnosable()throws Exception{
        var timeout=new java.net.SocketTimeoutException("PRIVATE_PASSWORD_AND_ORIGINAL_TEXT");
        timeout.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.postgresql.core.PGStream","receiveChar","PRIVATE_SOURCE",418),new StackTraceElement("outside.Driver","leak","PRIVATE_SOURCE",2)});
        var error=new java.sql.SQLException("PRIVATE_SQL", "08001",0,timeout);
        String output=capture(error);String first=output.split(" ",2)[0];assertTrue(first.startsWith("time="));assertDoesNotThrow(()->java.time.Instant.parse(first.substring(5)));
        assertTrue(output.contains("SQLState=08001"));assertTrue(output.contains("cause[1]=java.net.SocketTimeoutException"));assertTrue(output.contains("org.postgresql.core.PGStream.receiveChar:418"));
        assertFalse(output.contains("PRIVATE_"));assertFalse(output.contains("outside.Driver"));
    }
    @Test void diagnosticBoundsLongAndCyclicCauseChainsAndDriverFrames()throws Exception{
        Exception tail=new Exception("PRIVATE_TAIL");tail.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.postgresql.Driver","deepestSentinel","private",1)});
        StackTraceElement[] frames=new StackTraceElement[200];java.util.Arrays.fill(frames,new StackTraceElement("org.postgresql.core.PGStream","receiveChar","PRIVATE_FILE",418));
        for(int i=0;i<100;i++){Exception wrapper=new Exception("PRIVATE_LAYER",tail);wrapper.setStackTrace(frames);tail=wrapper;}
        String output=capture(tail);assertTrue(output.length()<12000);assertFalse(output.contains("deepestSentinel"));assertTrue(output.contains("causeChainTruncated=true"));assertTrue(output.contains("stackTruncated=true"));assertFalse(output.contains("PRIVATE_"));
        Exception a=new Exception("PRIVATE_A"),b=new Exception("PRIVATE_B");a.initCause(b);b.initCause(a);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->{String cyclic=capture(a);assertTrue(cyclic.contains("causeChainTruncated=true"));assertFalse(cyclic.contains("PRIVATE_"));});
    }
    private static String capture(Exception error)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();PrintStream saved=System.err;
        try{System.setErr(new PrintStream(bytes,true,StandardCharsets.UTF_8));ProductionDiagnostics.log("worker",error);}finally{System.setErr(saved);}
        return bytes.toString(StandardCharsets.UTF_8);
    }

}
