package dev.ailiao.expert;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.SocketFactory;
import javax.net.ssl.SSLHandshakeException;

/** In-memory PostgreSQL wire fixture. No listener, DNS lookup, database or real socket connection. */
public final class MemoryPostgresSocketFactory extends SocketFactory {
    enum Fault { NONE, SSL_TIMEOUT, TLS_FAILURE, AUTH_FAILURE, COMMIT_TIMEOUT }
    static final List<WireSocket> opened=new ArrayList<>();
    static final Deque<Fault> faults=new ArrayDeque<>();
    static void reset(Fault... plan){opened.clear();faults.clear();faults.addAll(List.of(plan));}
    @Override public Socket createSocket(){
        WireSocket s=new WireSocket(faults.isEmpty()?Fault.NONE:faults.removeFirst());opened.add(s);return s;
    }
    @Override public Socket createSocket(String h,int p){throw new AssertionError("Network forbidden");}
    @Override public Socket createSocket(String h,int p,InetAddress a,int q){throw new AssertionError("Network forbidden");}
    @Override public Socket createSocket(InetAddress a,int p){throw new AssertionError("Network forbidden");}
    @Override public Socket createSocket(InetAddress a,int p,InetAddress b,int q){throw new AssertionError("Network forbidden");}

    static final class WireSocket extends Socket {
        final Fault fault;final List<String> queries=new ArrayList<>();
        final Deque<Byte> received=new ArrayDeque<>();
        boolean closed,sslRequested,started;int timeout;byte transaction='I';IOException readFailure;
        WireSocket(Fault fault){this.fault=fault;}
        final ByteArrayOutputStream output=new ByteArrayOutputStream(){
            @Override public void flush() throws IOException {byte[] bytes=toByteArray();reset();consume(bytes);}
        };
        void consume(byte[] bytes) throws IOException {
            if(bytes.length==0)return;
            if(!sslRequested){
                if(!Arrays.equals(bytes,new byte[]{0,0,0,8,4,(byte)210,22,47}))throw new AssertionError("Expected SSLRequest");
                sslRequested=true;
                if(fault==Fault.SSL_TIMEOUT)readFailure=new SocketTimeoutException("fixture SSL timeout");
                else if(fault==Fault.TLS_FAILURE)readFailure=new SSLHandshakeException("fixture certificate failure");
                else received.add((byte)'N');
                return;
            }
            if(!started){
                if(ByteBuffer.wrap(bytes).getInt()!=bytes.length||ByteBuffer.wrap(bytes,4,4).getInt()!=196608)throw new AssertionError("Invalid startup packet");
                started=true;
                if(fault==Fault.AUTH_FAILURE){message('E',"SFATAL\0C28P01\0Mfixture authentication failed\0\0".getBytes(StandardCharsets.UTF_8));return;}
                message('R',new byte[4]);parameter("server_version","17.6");parameter("client_encoding","UTF8");parameter("standard_conforming_strings","on");parameter("integer_datetimes","on");ready();return;
            }
            var input=new DataInputStream(new ByteArrayInputStream(bytes));
            while(input.available()>0){
                int type=input.readUnsignedByte(),length=input.readInt();byte[] payload=input.readNBytes(length-4);
                if(payload.length!=length-4)throw new AssertionError("Truncated frontend message");
                if(type=='X')continue;
                if(type!='Q')throw new AssertionError("Fixture supports simple queries only: "+type);
                String sql=new String(payload,0,payload.length-1,StandardCharsets.UTF_8);queries.add(sql);
                String upper=sql.toUpperCase(Locale.ROOT);
                if(upper.startsWith("COMMIT")&&fault==Fault.COMMIT_TIMEOUT){readFailure=new SocketTimeoutException("fixture commit response lost");return;}
                if(upper.startsWith("BEGIN"))transaction='T';
                else if(upper.startsWith("COMMIT")||upper.startsWith("ROLLBACK"))transaction='I';
                String tag=upper.startsWith("INSERT")?"INSERT 0 1":upper.split(" ",2)[0];
                message('C',(tag+"\0").getBytes(StandardCharsets.UTF_8));ready();
            }
        }
        void parameter(String key,String value)throws IOException {message('S',(key+"\0"+value+"\0").getBytes(StandardCharsets.UTF_8));}
        void ready()throws IOException {message('Z',new byte[]{transaction});}
        void message(int type,byte[] payload)throws IOException {var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(type);out.writeInt(payload.length+4);out.write(payload);for(byte b:bytes.toByteArray())received.add(b);}
        @Override public boolean isConnected(){return true;}
        @Override public void connect(SocketAddress a,int t){throw new AssertionError("Network forbidden");}
        @Override public void connect(SocketAddress a){throw new AssertionError("Network forbidden");}
        @Override public void bind(SocketAddress a){throw new AssertionError("Network forbidden");}
        @Override public void setTcpNoDelay(boolean value){}
        @Override public void setKeepAlive(boolean value){}
        @Override public void setSoTimeout(int value){timeout=value;}
        @Override public int getSoTimeout(){return timeout;}
        @Override public int getSendBufferSize(){return 8192;}
        @Override public int getReceiveBufferSize(){return 8192;}
        @Override public OutputStream getOutputStream(){return output;}
        @Override public InputStream getInputStream(){return new InputStream(){
            @Override public int read()throws IOException {if(!received.isEmpty())return Byte.toUnsignedInt(received.removeFirst());if(readFailure!=null)throw readFailure;throw new AssertionError("Unexpected driver read without response");}
            @Override public int read(byte[] b,int off,int len)throws IOException {if(len==0)return 0;if(received.isEmpty())return read();int n=Math.min(len,received.size());for(int i=0;i<n;i++)b[off+i]=received.removeFirst();return n;}
        };}
        @Override public void close(){closed=true;}
    }
}
