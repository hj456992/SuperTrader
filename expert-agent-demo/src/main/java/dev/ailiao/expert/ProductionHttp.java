package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URLDecoder;
import java.util.*;

/** 在LabHttp完成Host/CSRF校验后调用；独立验收服务器复用相同路由。 */
final class ProductionHttp {
    private static final String BASE="/api/expert-production/v1";
    private final ProductionService service;
    ProductionHttp(ProductionService service){this.service=service;}
    boolean handle(HttpExchange exchange)throws java.io.IOException{
        String path=exchange.getRequestURI().getPath();if(!path.startsWith(BASE+"/"))return false;
        try{
            if(service==null)throw new ProductionException(503,"DATABASE_NOT_CONFIGURED","生产数据库未配置，无法保存或审核构建");
            String method=exchange.getRequestMethod();String[] parts=path.substring(BASE.length()+1).split("/");ObjectNode result;
            if(parts.length==1&&parts[0].equals("builds")&&method.equals("GET"))result=service.list();
            else if(parts.length>=2&&parts[0].equals("builds")){
                String id=parts[1];
                if(parts.length==2&&method.equals("PUT"))result=service.create(id,body(exchange));
                else if(parts.length==3&&parts[2].equals("snapshot")&&method.equals("GET"))result=service.snapshot(id);
                else if(parts.length==3&&parts[2].equals("messages")&&method.equals("POST"))result=service.message(id,body(exchange));
                else if(parts.length==3&&parts[2].equals("events")&&method.equals("GET")){Map<String,String> q=query(exchange);result=service.events(id,Long.parseLong(q.getOrDefault("afterSeq","0")),Integer.parseInt(q.getOrDefault("limit","100")));}
                else if(parts.length==4&&parts[2].equals("sources")&&method.equals("GET")){Map<String,String> q=query(exchange);result=service.source(id,parts[3],q.containsKey("startOffset")?Integer.valueOf(q.get("startOffset")):null,q.containsKey("endOffset")?Integer.valueOf(q.get("endOffset")):null);}
                else if(parts.length==6&&parts[2].equals("source-versions")&&parts[4].equals("pages")&&method.equals("GET")){int page=Integer.parseInt(parts[5]);byte[] pdf=Files.readAllBytes(service.original(id,parts[3],page));exchange.getResponseHeaders().set("Content-Type","application/pdf");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,pdf.length);exchange.getResponseBody().write(pdf);return true;}
                else if(parts.length==4&&parts[2].equals("original")&&method.equals("GET")){byte[] pdf=Files.readAllBytes(service.original(id,parts[3]));exchange.getResponseHeaders().set("Content-Type","application/pdf");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,pdf.length);exchange.getResponseBody().write(pdf);return true;}
                else throw new ProductionException(404,"NOT_FOUND","生产接口不存在");
            }else throw new ProductionException(404,"NOT_FOUND","生产接口不存在");
            send(exchange,result.path("httpStatus").asInt(200),result);
        }catch(ProductionDatabase.ConnectionUnavailable e){ProductionDiagnostics.log("HTTP connection unavailable",e);send(exchange,503,Json.object().set("error",Json.object().put("code","DATABASE_UNAVAILABLE").put("message","生产数据库暂时无法连接，请稍后刷新核对状态；重试提交时保留原请求标识。")));}
        catch(ProductionException e){send(exchange,e.httpStatus,Json.object().set("error",Json.object().put("code",e.code).put("message",e.getMessage())));}
        catch(IllegalArgumentException e){send(exchange,400,Json.object().set("error",Json.object().put("code","INVALID_REQUEST").put("message",e.getMessage()==null?"请求无效":e.getMessage())));}
        catch(Exception e){ProductionDiagnostics.log("HTTP",e);send(exchange,500,Json.object().set("error",Json.object().put("code","INTERNAL_ERROR").put("message","操作未完成，请检查服务状态或稍后重试")));}
        return true;
    }
    private static ObjectNode body(HttpExchange e)throws Exception{byte[] data=e.getRequestBody().readNBytes(60001);if(data.length>60000)throw new ProductionException(413,"TOO_LARGE","请求内容过长");return Json.parse(new String(data,StandardCharsets.UTF_8));}
    private static Map<String,String> query(HttpExchange e){Map<String,String> out=new HashMap<>();String raw=e.getRequestURI().getRawQuery();if(raw!=null)for(String part:raw.split("&")){String[] kv=part.split("=",2);if(kv.length==2)out.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return out;}
    private static void send(HttpExchange e,int status,ObjectNode value)throws java.io.IOException{byte[] data=Json.MAPPER.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);}
}
