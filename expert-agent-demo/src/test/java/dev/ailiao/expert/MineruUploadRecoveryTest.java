package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class MineruUploadRecoveryTest {
 @TempDir Path dir;
 @Test void failedUploadSurvivesRestartAndResendsExactBytesToSameBatch() throws Exception {
  Path folder=seed(1);byte[] original=Files.readAllBytes(folder.resolve("original.pdf"));
  try(Fixture f=new Fixture()) {
   try(Jobs jobs=new Jobs()) {var service=service(jobs,f);service.resume("i-1");var state=await(service);assertEquals("paused",state.path("status").asText());assertEquals(1,f.posts.get());assertEquals(1,f.puts.get());assertFalse(state.toString().contains("private-signature"));}
   try(Jobs jobs=new Jobs()) {var service=service(jobs,f);service.resume("i-1");var state=await(service);assertEquals("completed",state.path("status").asText(),state.toString());assertEquals(1,f.posts.get(),"resume must reuse the existing batch");assertEquals(2,f.puts.get());assertArrayEquals(original,f.lastBytes.get());assertFalse(state.toString().contains("private-signature"));assertFalse(new LabStore(dir).state().toString().contains("private-signature"));}
  }
 }
 @Test void savedTicketRecoversBatchWhenJournalWriteWasInterrupted() throws Exception {
  Path folder=seed(1);
  try(Fixture f=new Fixture()) {
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");await(s);}
   var journal=Json.parse(Files.readString(folder.resolve("state.json")));journal.remove("batchId");Files.writeString(folder.resolve("state.json"),journal.toString());
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);assertTrue(s.get("i-1").path("canResume").asBoolean());s.resume("i-1");assertEquals("completed",await(s).path("status").asText());assertEquals(1,f.posts.get());assertEquals(2,f.puts.get());}
  }
 }
 @Test void oversizedAcceptedBatchIsQueriedAndDownloadedBeforeLocalValidation() throws Exception {
  Path folder=seed(312);var journal=Json.parse(Files.readString(folder.resolve("state.json")));journal.put("batchId","b-1");Files.writeString(folder.resolve("state.json"),journal.toString());
  try(Fixture f=new Fixture();Jobs jobs=new Jobs()) {f.accepted.set(true);var s=service(jobs,f);s.resume("i-1");var result=await(s);assertTrue(Files.isRegularFile(folder.resolve("result.zip")));assertFalse(result.path("pageLimitExceeded").asBoolean());assertTrue(result.path("canResume").asBoolean());assertEquals(0,f.posts.get());assertEquals(0,f.puts.get());}
 }
 @Test void changedFileCannotBeResentUsingSavedTicket() throws Exception {
  Path folder=seed(1);
  try(Fixture f=new Fixture()) {
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");var failed=await(s);assertEquals("MINERU_HTTP_ERROR",failed.path("errorDetails").path("code").asText());assertEquals(503,failed.path("errorDetails").path("httpStatus").asInt());}
   Path ticket=folder.resolve("original.pdf.upload.json");
   assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(ticket)));
   Files.writeString(folder.resolve("original.pdf"),"%PDF-1.7 changed");
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");var result=await(s);assertEquals("paused",result.path("status").asText());assertTrue(result.path("error").asText().contains("不一致"));assertFalse(result.has("errorDetails"));assertEquals(1,f.posts.get());assertEquals(1,f.puts.get());}
  }
 }
 @Test void acceptedRemoteUploadIsQueriedWithoutSendingFileAgain() throws Exception {
  Path folder=seed(1);
  try(Fixture f=new Fixture()) {
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");await(s);}
   f.accepted.set(true);
   try(Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");assertEquals("completed",await(s).path("status").asText());assertEquals(1,f.posts.get());assertEquals(1,f.puts.get());}
  }
 }
 @Test void oldWaitingFileWithoutTicketStopsInsteadOfPollingForever() throws Exception {
  Path folder=seed(1);var state=Json.parse(Files.readString(folder.resolve("state.json")));state.put("batchId","b-1");Files.writeString(folder.resolve("state.json"),state.toString());
  try(Fixture f=new Fixture();Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");var result=await(s);assertEquals("paused",result.path("status").asText());assertTrue(result.path("error").asText().contains("上传地址"));assertEquals(0,f.posts.get());assertEquals(0,f.puts.get());}
 }
 @Test void oversizedOriginalIsStoppedBeforeCloudSubmissionAndOffersSplit() throws Exception {
  seed(312);
  try(Fixture f=new Fixture();Jobs jobs=new Jobs()) {var s=service(jobs,f);s.resume("i-1");var result=await(s);assertEquals("paused",result.path("status").asText());assertTrue(result.path("canSplit").asBoolean());assertFalse(result.path("canResume").asBoolean());assertEquals(0,f.posts.get());assertEquals(0,f.puts.get());}
 }
 private Path seed(int pages) throws Exception {Path folder=dir.resolve("imports/i-1");Files.createDirectories(folder);Files.writeString(folder.resolve("original.pdf"),"%PDF-1.7 original unchanged");Files.writeString(folder.resolve("state.json"),Json.object().put("id","i-1").put("status","paused").put("pageCount",pages).put("title","book").put("filename","book.pdf").toString());return folder;}
 private MineruImport service(Jobs jobs,Fixture f) throws Exception {return new MineruImport(dir,new LabStore(dir),jobs,new MineruClient(URI.create(f.base),()->"secret-token"),new PdfImport("python3",Path.of("extract_pdf.py"),dir.resolve("tmp")));}
 private ObjectNode await(MineruImport s) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);ObjectNode r;do{r=s.get("i-1");if(!r.path("status").asText().equals("running"))return r;Thread.sleep(10);}while(System.nanoTime()<end);fail("import did not settle: "+r);return null;}
 static class Fixture implements AutoCloseable {
  final HttpServer server;final String base;final AtomicInteger posts=new AtomicInteger(),puts=new AtomicInteger();final AtomicBoolean accepted=new AtomicBoolean();final AtomicReference<byte[]> lastBytes=new AtomicReference<>();
  Fixture() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();server.createContext("/",e->{try{String path=e.getRequestURI().getPath();byte[] body;
   if(path.equals("/api/v4/file-urls/batch")){posts.incrementAndGet();e.getRequestBody().readAllBytes();body=("{\"code\":0,\"data\":{\"batch_id\":\"b-1\",\"file_urls\":[\""+base+"/upload?signature=private-signature\"]}}").getBytes();}
   else if(path.equals("/upload")){lastBytes.set(e.getRequestBody().readAllBytes());if(puts.incrementAndGet()==1){e.sendResponseHeaders(503,-1);e.close();return;}accepted.set(true);e.sendResponseHeaders(200,-1);e.close();return;}
   else if(path.startsWith("/api/v4/extract-results/batch/")){body=("{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\""+(accepted.get()?"done":"waiting-file")+"\",\"full_zip_url\":\""+base+"/result.zip\"}]}}").getBytes();}
   else if(path.equals("/result.zip")){var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("layout.json"));z.write("{\"pdf_info\":[{\"page_idx\":0}]}".getBytes());z.closeEntry();z.putNextEntry(new ZipEntry("content_list.json"));z.write("[{\"page_idx\":0,\"type\":\"text\",\"text\":\"complete book\"}]".getBytes());z.closeEntry();}body=out.toByteArray();}
   else{e.sendResponseHeaders(404,-1);e.close();return;}
   e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
  }catch(Exception error){e.close();}});server.start();}
  public void close(){server.stop(0);}
 }
}
