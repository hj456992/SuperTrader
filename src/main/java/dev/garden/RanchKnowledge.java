package dev.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Local passage retrieval. Books supply methods, never evidence about a person. */
final class RanchKnowledge {
    private final RanchStore store;
    RanchKnowledge(RanchStore store) {this.store=store;}

    ArrayNode retrieve(ObjectNode state,String query,int limit) throws Exception {
        var passages=Store.JSON.createArrayNode();
        for(var metadata:state.path("library")) {
            if(!metadata.path("enabled").asBoolean())continue;
            var book=store.book(metadata.path("id").asText());
            book.path("chunks").forEach(passages::add);
        }
        return rank(passages,query,Math.max(0,Math.min(8,limit)));
    }
    synchronized Object command(String action,JsonNode body) throws Exception {
        if(!body.path("revision").isIntegralNumber())throw new IllegalArgumentException("缺少资料版本，请刷新后重试。");
        long revision=body.path("revision").asLong();
        var state=store.read();
        if(state.path("revision").asLong()!=revision)throw new IllegalStateException("资料已更新，请刷新后重试。");
        if(action.equals("knowledge-upload")) {
            if(state.path("library").size()>=30)throw new IllegalArgumentException("书架最多保存30份资料，请先整理已有内容。");
            var filename=GardenService.text(body,"filename",200);
            var title=body.path("title").asText("").strip();if(title.isBlank())title=filename;
            if(title.length()>200)throw new IllegalArgumentException("资料名称最多200字。");
            var raw=GardenService.text(body,"dataBase64",14_000_000);byte[] bytes;
            try{bytes=Base64.getDecoder().decode(raw);}catch(IllegalArgumentException e){throw new IllegalArgumentException("文件编码无效，请重新选择文件。");}
            if(bytes.length==0||bytes.length>10*1024*1024)throw new IllegalArgumentException("请选择10MB以内的非空文件。");
            var pages=extract(filename,bytes);var id=UUID.randomUUID().toString();var chunks=chunk(id,title,pages);
            if(chunks.isEmpty())throw new IllegalArgumentException("没有提取到可检索的文字。扫描PDF请先转换为可选中文字的版本。");
            int characters=0;for(var page:pages)characters+=page.path("text").asText().length();
            var metadata=Store.JSON.createObjectNode().put("id",id).put("title",title).put("filename",filename)
                .put("characters",characters).put("chunkCount",chunks.size()).put("enabled",true).put("createdAt",Instant.now().toString());
            var book=metadata.deepCopy();book.set("chunks",chunks);store.putBook(id,book);
            try{return store.update(revision,s->{((ArrayNode)s.path("library")).add(metadata);invalidateStrategies(s);});}
            catch(Exception e){store.deleteBook(id);throw e;}
        }
        var id=GardenService.text(body,"id",100);
        if(action.equals("knowledge-toggle")&&!body.path("enabled").isBoolean())throw new IllegalArgumentException("请选择资料启用状态。");
        if(!Set.of("knowledge-toggle","knowledge-delete").contains(action))throw new IllegalArgumentException("不支持的书架操作。");
        var updated=store.update(revision,s->{
            var library=(ArrayNode)s.path("library");int found=-1;
            for(int i=0;i<library.size();i++)if(library.get(i).path("id").asText().equals(id))found=i;
            if(found<0)throw new IllegalArgumentException("资料已不存在。");
            if(action.equals("knowledge-delete"))library.remove(found);
            else ((ObjectNode)library.get(found)).put("enabled",body.path("enabled").asBoolean());
            invalidateStrategies(s);
            // Deletion also removes retained quotes, not only their visible metadata.
            if(action.equals("knowledge-delete"))for(var person:s.path("people")) {
                var strategies=(ArrayNode)person.path("strategies");
                for(int i=strategies.size()-1;i>=0;i--) {
                    boolean references=false;
                    for(var passage:strategies.get(i).path("knowledge"))if(passage.path("documentId").asText().equals(id))references=true;
                    if(references)strategies.remove(i);
                }
            }
        });
        if(action.equals("knowledge-delete"))store.deleteBook(id);
        return updated;
    }
    private static void invalidateStrategies(ObjectNode state) {
        for(var p:state.path("people"))for(var strategy:p.path("strategies"))((ObjectNode)strategy).put("stale",true);
    }
    private static ArrayNode extract(String filename,byte[] bytes) throws Exception {
        var extension=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Set.of("txt","md","pdf","docx","epub").contains(extension))throw new IllegalArgumentException("支持 TXT、Markdown、PDF、DOCX 和 EPUB 文件。");
        var dir=Files.createTempDirectory("aichat-book-");
        Path input=dir.resolve("input."+extension), output=dir.resolve("result.json"), errors=dir.resolve("error.log");
        try {
            Files.write(input,bytes);
            var python=System.getenv().getOrDefault("GARDEN_PYTHON","/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3");
            var script=Path.of(System.getenv().getOrDefault("GARDEN_EXTRACTOR","knowledge/extract.py")).toAbsolutePath();
            var process=new ProcessBuilder(python,script.toString(),input.toString(),output.toString()).redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(40,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalArgumentException("资料解析超时，请拆分文件后重试。");}
            if(!Files.isRegularFile(output)||Files.size(output)>12_000_000)throw new IllegalArgumentException("资料解析失败，请检查文件格式。");
            var result=Store.JSON.readTree(Files.readString(output,StandardCharsets.UTF_8));
            if(result.has("error"))throw new IllegalArgumentException(result.path("error").asText());
            if(!result.path("pages").isArray())throw new IllegalArgumentException("没有可读取的文字。");
            return (ArrayNode)result.path("pages");
        } finally {
            Files.deleteIfExists(input);Files.deleteIfExists(output);Files.deleteIfExists(errors);Files.deleteIfExists(dir);
        }
    }
    static ArrayNode chunk(String documentId,String title,ArrayNode pages) {
        var result=Store.JSON.createArrayNode();int index=0;
        for(var page:pages) {
            var text=page.path("text").asText().replaceAll("[\\t ]+"," ").strip();
            for(int start=0;start<text.length();start+=780) {
                int end=Math.min(start+900,text.length());
                result.addObject().put("id","K-"+documentId+"-"+index++).put("documentId",documentId).put("title",title)
                    .put("location",page.path("location").asText("正文")).put("text",text.substring(start,end));
                if(end==text.length())break;
            }
        }
        return result;
    }
    private static Set<String> tokens(String text) {
        var result=new HashSet<String>();
        var matcher=Pattern.compile("[a-z0-9]{2,}|[\\p{IsHan}]+").matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            var part=matcher.group();
            if(part.codePointAt(0)<128)result.add(part);
            else for(int i=0;i<part.length()-1;i++)result.add(part.substring(i,i+2));
        }
        return result;
    }
    static ArrayNode rank(ArrayNode chunks,String query,int limit) {
        var terms=tokens(query.substring(0,Math.min(query.length(),5000)));
        var frequencies=new HashMap<String,Integer>();var docs=new ArrayList<Set<String>>();
        for(var c:chunks){var words=tokens(c.path("title").asText()+" "+c.path("text").asText());docs.add(words);for(var t:words)if(terms.contains(t))frequencies.merge(t,1,Integer::sum);}
        record Scored(int index,double score){}
        var scores=new ArrayList<Scored>();
        for(int i=0;i<chunks.size();i++) {
            double score=0;
            for(var term:terms)if(docs.get(i).contains(term))score+=Math.log(1+(chunks.size()-frequencies.get(term)+0.5)/(frequencies.get(term)+0.5));
            if(score>0)scores.add(new Scored(i,score));
        }
        scores.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        var result=Store.JSON.createArrayNode();scores.stream().limit(Math.max(0,limit)).forEach(s->result.add(chunks.get(s.index()).deepCopy()));return result;
    }
}
