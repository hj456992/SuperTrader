package dev.ailiao.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MineruImportTest {
    @TempDir Path dir;
    @Test void staleAttemptCannotChangeNewAttemptsJournal() throws Exception {
        Path folder=dir.resolve("imports/i-1");Files.createDirectories(folder);
        Files.writeString(folder.resolve("state.json"),"{\"id\":\"i-1\",\"status\":\"paused\",\"batchId\":\"batch-1\",\"pageCount\":1}");
        CountDownLatch queried=new CountDownLatch(1), release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v4/extract-results/batch/batch-1",e->{queried.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            byte[] body="{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"waiting-file\"}]}}".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        server.start();
        try(var jobs=new Jobs()) {
            var service=new MineruImport(dir,new LabStore(dir),jobs,new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"token"),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            String currentId=service.resume("i-1").path("jobId").asText();
            assertTrue(queried.await(3,TimeUnit.SECONDS));
            Jobs.Job oldJob=new Jobs.Job("import","ocr"), newJob=jobs.get(currentId);
            assertFalse(service.updateIfCurrent("i-1",oldJob,r->r.put("status","paused")));
            assertEquals("running",service.get("i-1").path("status").asText());
            assertEquals(currentId,service.get("i-1").path("jobId").asText());
            assertTrue(service.updateIfCurrent("i-1",newJob,r->r.put("phase","querying")));
            assertEquals("querying",service.get("i-1").path("phase").asText());
        } finally {release.countDown();server.stop(0);}
    }
    @Test void resumedWaitingFileReportsUncertainUploadWithoutReupload() throws Exception {
        Path folder=dir.resolve("imports/i-1");Files.createDirectories(folder);
        Files.writeString(folder.resolve("state.json"),"{\"id\":\"i-1\",\"status\":\"paused\",\"batchId\":\"batch-1\",\"pageCount\":1}");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger uploads=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/v4/extract-results/batch/batch-1",e->{
            byte[] body="{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"waiting-file\"}]}}".getBytes();
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        server.createContext("/api/v4/file-urls/batch",e->{uploads.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});
        server.start();
        try(var jobs=new Jobs()) {
            var service=new MineruImport(dir,new LabStore(dir),jobs,new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"token"),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            String jobId=service.resume("i-1").path("jobId").asText();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!service.get("i-1").path("message").asText().contains("上次上传结果不确定") && System.nanoTime()<deadline) Thread.sleep(10);
            assertTrue(service.get("i-1").path("message").asText().contains("仅查询原任务"));
            assertEquals(0,uploads.get());
            service.cancelJob(jobId);
        } finally {server.stop(0);}
    }
    @Test void durableImportIsCompletedWhenDeadlineExpiresBeforeJournalFinish() throws Exception {
        Path folder=dir.resolve("imports/i-1");Files.createDirectories(folder);
        Files.writeString(folder.resolve("state.json"),"{\"id\":\"i-1\",\"status\":\"paused\",\"batchId\":\"batch-1\",\"pageCount\":1}");
        CountDownLatch queried=new CountDownLatch(1), release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v4/extract-results/batch/batch-1",e->{queried.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            byte[] body="{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"waiting-file\"}]}}".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        server.start();
        try(var jobs=new Jobs()) {
            LabStore store=new LabStore(dir);
            var service=new MineruImport(dir,store,jobs,new MineruClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"token"),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            String jobId=service.resume("i-1").path("jobId").asText();
            assertTrue(queried.await(3,TimeUnit.SECONDS));
            var pages=Json.array().add(Json.object().put("page",1).put("text","durable text"));
            var committed=store.importDocument("book","",pages,"book.pdf","%PDF-1.7".getBytes(),Json.object().put("provider","mineru"),"i-1");
            Jobs.Job job=jobs.get(jobId);
            var deadline=Jobs.Job.class.getDeclaredField("deadline");deadline.setAccessible(true);deadline.setLong(job,System.nanoTime()-1);
            var finish=MineruImport.class.getDeclaredMethod("finish",String.class,Jobs.Job.class,com.fasterxml.jackson.databind.node.ObjectNode.class);finish.setAccessible(true);
            finish.invoke(service,"i-1",job,committed);
            assertEquals("completed",service.get("i-1").path("status").asText());
            assertEquals(committed.path("versionId").asText(),service.get("i-1").path("result").path("versionId").asText());
            release.countDown();
            long settled=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(job.running() && System.nanoTime()<settled) Thread.sleep(10);
            assertFalse(job.running(), "worker must stop writing before temporary journal cleanup");
        } finally {release.countDown();server.stop(0);}
    }
    @Test void restartTurnsInterruptedImportIntoResumablePauseWithoutSubmitting() throws Exception {
        Path journal=dir.resolve("imports/i-1");Files.createDirectories(journal);
        Files.writeString(journal.resolve("state.json"),"{\"id\":\"i-1\",\"status\":\"running\",\"batchId\":\"batch-1\",\"phase\":\"running\",\"jobId\":\"old-job\"}");
        try(var jobs=new Jobs()) {
            var service=new MineruImport(dir,new LabStore(dir),jobs,new MineruClient(),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));
            var value=service.list().get(0);assertEquals("paused",value.path("status").asText());assertEquals("batch-1",value.path("batchId").asText());assertTrue(value.path("canResume").asBoolean());assertFalse(value.has("jobId"));
        }
    }
    @Test void ocrUsesOneSlotAndCancelledImportCannotCommit() throws Exception {
        try(var jobs=new Jobs()) {
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            var first=jobs.start("import","ocr",job->{entered.countDown();Thread.sleep(60000);return job.commit(()->Json.object().put("shouldNotExist",true));});
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,()->jobs.start("import","ocr",job->Json.object()));
            first.cancel();assertThrows(java.util.concurrent.CancellationException.class,()->first.commit(()->Json.object()));
            assertFalse(first.snapshot().has("result"));
        }
    }
}
