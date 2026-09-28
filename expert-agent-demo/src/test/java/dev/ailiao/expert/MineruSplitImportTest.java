package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class MineruSplitImportTest {
    @TempDir Path dir;
    // Without per-part recovery this resubmits the whole book and cannot restore page 201.
    @Test void resumesSecondBatchAndCommitsOneCompleteVersionWithOriginalPdf() throws Exception {
        Path folder=seed(true); byte[] original=Files.readAllBytes(folder.resolve("original.pdf"));
        List<String> calls=new CopyOnWriteArrayList<>();
        HttpServer server=fixture(calls,false);server.start();
        try(Jobs jobs=new Jobs()) {
            LabStore store=new LabStore(dir);var imports=service(jobs,store,server);
            imports.resume("split"); ObjectNode value=await(imports);
            assertEquals("completed",value.path("status").asText(),value.toString());
            assertEquals(List.of("GET /api/v4/extract-results/batch/b-2","GET /zip/2"),calls);
            assertEquals(1,store.state().path("documents").size());
            var version=store.documentVersion(value.path("result").path("versionId").asText());
            assertEquals(201,version.path("pageCount").asInt());
            assertEquals(201,value.path("report").path("pageCount").asInt());
            var parsed=Json.MAPPER.readTree(Files.readAllBytes(folder.resolve("parsed.json")));
            assertEquals("second",parsed.path("pages").get(200).path("text").asText());
            assertEquals(201,parsed.path("pages").get(200).path("page").asInt());
            assertEquals(200,parsed.path("pages").get(200).path("blocks").get(0).path("page_idx").asInt());
            assertArrayEquals(original,Files.readAllBytes(folder.resolve("original.pdf")));
        } finally {server.stop(0);}
    }
    // Starting part 2 before part 1's result is safely saved violates approved sequential processing.
    @Test void processesPartsSequentiallyAndFailureNeverCommitsPartialBook() throws Exception {
        seed(false);List<String> calls=new CopyOnWriteArrayList<>();HttpServer server=fixture(calls,true);server.start();
        try(Jobs jobs=new Jobs()) {
            LabStore store=new LabStore(dir);var imports=service(jobs,store,server);
            imports.resume("split");ObjectNode value=await(imports);
            assertEquals("failed",value.path("status").asText(),value.toString());
            assertEquals(0,store.state().path("documents").size());
            assertEquals(List.of("POST /api/v4/file-urls/batch","PUT /upload/1","GET /api/v4/extract-results/batch/b-1","GET /zip/1","POST /api/v4/file-urls/batch","PUT /upload/2","GET /api/v4/extract-results/batch/b-2"),calls);
            assertTrue(Files.isRegularFile(dir.resolve("imports/split/part-001.zip")));
            assertEquals(200,value.path("extractedPages").asInt());
            assertFalse(value.path("canResume").asBoolean());
        } finally {server.stop(0);}
    }
    @Test void unknownPartSubmissionDoesNotOfferUnsafeResubmission() throws Exception {
        Path folder=seed(false);var state=(ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(folder.resolve("state.json")));
        ((ObjectNode)state.path("parts").get(0)).put("submissionAttempted",true);
        Files.writeString(folder.resolve("state.json"),state.toString());
        try(Jobs jobs=new Jobs()) {
            var imports=new MineruImport(dir,new LabStore(dir),jobs,new MineruClient(),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            assertFalse(imports.get("split").path("canResume").asBoolean());
        }
    }
    @Test void simultaneousSplitClicksReserveOneChildBeforeStartingCloudWork() throws Exception {
        Path folder=dir.resolve("imports/rejected");Files.createDirectories(folder);
        Files.writeString(folder.resolve("original.pdf"),"%PDF-1.7 placeholder");
        Files.writeString(folder.resolve("state.json"),Json.object().put("id","rejected").put("title","book").put("status","failed").put("remoteFailed",true).put("pageCount",201).put("error","limit 200 pages").toString());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var worker=Executors.newSingleThreadExecutor();
        try(Jobs jobs=new Jobs()) {
            var client=new MineruClient(URI.create("http://127.0.0.1:1"),()->{
                if(calls.incrementAndGet()>1)throw new IOException("duplicate split reached credential check");
                entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}return "token";
            });
            var imports=new MineruImport(dir,new LabStore(dir),jobs,client,new PdfImport("/usr/bin/false",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            Future<ObjectNode> first=worker.submit(()->imports.split("rejected"));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try {assertThrows(IllegalArgumentException.class,()->imports.split("rejected"));assertEquals(1,calls.get());}
            finally {release.countDown();}
            ObjectNode started=first.get(3,TimeUnit.SECONDS);
            String child=started.path("importId").asText();
            assertEquals(child,imports.split("rejected").path("importId").asText());
            assertEquals(2,imports.list().size());
            Jobs.Job job=jobs.get(started.path("jobId").asText());
            long settled=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(job.running() && System.nanoTime()<settled) Thread.sleep(10);
            assertFalse(job.running(), "worker must stop writing before temporary journal cleanup");
        } finally {release.countDown();worker.shutdownNow();}
    }
    private Path seed(boolean firstDone) throws Exception {
        Path folder=dir.resolve("imports/split");Files.createDirectories(folder);
        Files.writeString(folder.resolve("original.pdf"),"%PDF-1.7 original unchanged");
        Files.writeString(folder.resolve("part-001.pdf"),"%PDF-1.7 first");Files.writeString(folder.resolve("part-002.pdf"),"%PDF-1.7 second");
        ObjectNode state=Json.object().put("id","split").put("status","paused").put("splitSourceId","old").put("pageCount",201).put("title","book").put("filename","book.pdf");
        state.set("parameters",MineruClient.parameters("original.pdf","split"));
        var parts=state.putArray("parts");
        var one=parts.addObject().put("index",0).put("startPage",1).put("endPage",200).put("pageCount",200).put("filename","part-001.pdf");
        var two=parts.addObject().put("index",1).put("startPage",201).put("endPage",201).put("pageCount",1).put("filename","part-002.pdf");
        if(firstDone){one.put("batchId","b-1").put("uploadedAt","saved").put("status","completed");two.put("batchId","b-2").put("uploadedAt","saved");Files.write(folder.resolve("part-001.zip"),zip(200,"first"));}
        Files.writeString(folder.resolve("state.json"),state.toString());return folder;
    }
    private MineruImport service(Jobs jobs,LabStore store,HttpServer server) throws Exception {
        return new MineruImport(dir,store,jobs,new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"fixture-token"),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
    }
    private ObjectNode await(MineruImport imports) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);ObjectNode value;
        do {value=imports.get("split");if(!value.path("status").asText().equals("running"))return value;Thread.sleep(10);}while(System.nanoTime()<until);
        fail("split import timed out: "+value);return null;
    }
    private HttpServer fixture(List<String> calls,boolean failSecond) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var submissions=new java.util.concurrent.atomic.AtomicInteger();String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",e->{try {
            String path=e.getRequestURI().getPath();calls.add(e.getRequestMethod()+" "+path);byte[] bytes;
            if(path.equals("/api/v4/file-urls/batch")) {int n=submissions.incrementAndGet();e.getRequestBody().readAllBytes();bytes=("{\"code\":0,\"data\":{\"batch_id\":\"b-"+n+"\",\"file_urls\":[\""+base+"/upload/"+n+"\"]}}").getBytes();}
            else if(path.startsWith("/upload/")){e.getRequestBody().readAllBytes();bytes=new byte[0];}
            else if(path.startsWith("/api/v4/extract-results/batch/b-")){String n=path.substring(path.length()-1);String row=failSecond&&n.equals("2")?"{\"state\":\"failed\",\"err_msg\":\"fixture failure\"}":"{\"state\":\"done\",\"full_zip_url\":\""+base+"/zip/"+n+"\"}";bytes=("{\"code\":0,\"data\":{\"extract_result\":["+row+"]}}").getBytes();}
            else if(path.equals("/zip/1"))bytes=zip(200,"first");else if(path.equals("/zip/2"))bytes=zip(1,"second");
            else {e.sendResponseHeaders(404,-1);e.close();return;}
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        }catch(Exception ex){e.close();}});return server;
    }
    private byte[] zip(int count,String text) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){
            var pages=Json.array();for(int i=0;i<count;i++)pages.addObject().put("page_idx",i);
            zip.putNextEntry(new ZipEntry("layout.json"));zip.write(Json.object().set("pdf_info",pages).toString().getBytes());zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content_list.json"));zip.write(Json.array().add(Json.object().put("page_idx",0).put("type","text").put("text",text)).toString().getBytes());zip.closeEntry();
        }return bytes.toByteArray();
    }
}
