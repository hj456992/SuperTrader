package dev.ailiao.expert;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLSocketFactory;

/** Redis is disposable for reads/events; configured quota failure is fail-closed. */
final class ProductionRedis {
    private final URI uri;
    private final String password;
    ProductionRedis(String url,String password) {
        uri=url==null||url.isBlank()?null:URI.create(url);
        if(uri!=null && (!Set.of("redis","rediss").contains(uri.getScheme())||uri.getHost()==null))throw new IllegalArgumentException("Invalid Redis URL");
        this.password=password==null?"":password;
    }
    static ProductionRedis fromEnvironment(){return new ProductionRedis(System.getenv("EXPERT_REDIS_URL"),System.getenv("EXPERT_REDIS_PASSWORD"));}
    boolean enabled(){return uri!=null;}
    Optional<String> get(String key){try {Object v=command("GET","ep:cache:"+key);return v instanceof String s?Optional.of(s):Optional.empty();}catch(Exception e){return Optional.empty();}}
    void cache(String key,String value,Duration ttl){try{if(ttl.toMillis()>0)command("SET","ep:cache:"+key,value,"PX",Long.toString(ttl.toMillis()));}catch(Exception ignored){}}
    void notifyChanged(String buildId,long seq){try{command("PUBLISH","ep:events:"+buildId,Long.toString(seq));}catch(Exception ignored){}}
    boolean permit(String provider,String alias,int limit,Duration window)throws Exception {
        if(limit<=0||window.toMillis()<=0)throw new IllegalArgumentException("Positive quota required");
        if(!enabled())return true;
        String identity=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((provider+"\n"+alias).getBytes(StandardCharsets.UTF_8)));
        Object count=command("EVAL","local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n", "1","ep:quota:"+identity,Long.toString(window.toMillis()));
        if(!(count instanceof Long n))throw new IOException("Redis quota returned invalid result");return n<=limit;
    }
    private Object command(String...args)throws IOException {
        if(!enabled())return null;
        int port=uri.getPort()<0?6379:uri.getPort();
        Socket raw=new Socket();raw.connect(new InetSocketAddress(uri.getHost(),port),1000);raw.setSoTimeout(1500);
        Socket connection=raw;
        try {
            if("rediss".equals(uri.getScheme())){
                var tls=(javax.net.ssl.SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,uri.getHost(),port,true);
                var params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);tls.startHandshake();connection=tls;
            }
            try(Socket socket=connection;var in=new BufferedInputStream(socket.getInputStream());var out=new BufferedOutputStream(socket.getOutputStream())){
                String auth=password;String username=null;
                if(uri.getUserInfo()!=null){String[]parts=uri.getUserInfo().split(":",2);if(parts.length==2){username=parts[0];if(auth.isBlank())auth=parts[1];}else if(auth.isBlank())auth=parts[0];}
                if(!auth.isBlank()){write(out,username==null||username.isBlank()?new String[]{"AUTH",auth}:new String[]{"AUTH",username,auth});read(in);}
                if(uri.getPath()!=null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())){String db=uri.getPath().substring(1);if(!db.matches("[0-9]+"))throw new IOException("Invalid Redis database");write(out,new String[]{"SELECT",db});read(in);}
                write(out,args);return read(in);
            }
        }finally {raw.close();}
    }
    private static void write(OutputStream out,String[]args)throws IOException {
        out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
        for(String arg:args){byte[]bytes=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write('\r');out.write('\n');}out.flush();
    }
    private static Object read(InputStream in)throws IOException {
        int type=in.read();if(type<0)throw new EOFException("Redis disconnected");String line=line(in);
        return switch(type){case '+'->line;case '-'->throw new IOException("Redis command failed");case ':'->Long.parseLong(line);case '$'->{int n=Integer.parseInt(line);if(n==-1)yield null;if(n<0||n>16*1024*1024)throw new IOException("Invalid Redis response size");byte[]bytes=in.readNBytes(n);if(bytes.length!=n||in.read()!='\r'||in.read()!='\n')throw new EOFException("Incomplete Redis response");yield new String(bytes,StandardCharsets.UTF_8);}default->throw new IOException("Unexpected Redis response");};
    }
    private static String line(InputStream in)throws IOException {
        var bytes=new ByteArrayOutputStream();for(int n=0;n<8192;n++){int b=in.read();if(b<0)throw new EOFException();if(b=='\r'){if(in.read()!='\n')throw new IOException("Malformed Redis response");return bytes.toString(StandardCharsets.UTF_8);}bytes.write(b);}throw new IOException("Redis response too long");
    }
}
