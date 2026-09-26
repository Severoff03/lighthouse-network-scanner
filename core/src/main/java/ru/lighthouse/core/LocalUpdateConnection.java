package ru.lighthouse.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Narrow HTTP/1.1 GET transport for Mothman's Content-Length responses on a bound LAN socket. */
public final class LocalUpdateConnection extends HttpURLConnection {
    public interface SocketFactory { Socket create() throws IOException; }
    private final SocketFactory factory;
    private Socket socket;
    private InputStream body;
    private long length=-1;
    public LocalUpdateConnection(URL url,SocketFactory factory)throws IOException {
        super(url);this.factory=factory;
        String base="http://"+url.getHost()+":"+url.getPort()+"/updates/"+MothmanUpdates.ID+"/";
        if(!url.toString().startsWith(base))throw new IOException("Not a Mothman URL");
        MothmanUpdates.endpoint(base,url.toString().substring(base.length()));
    }
    @Override public void connect()throws IOException {
        if(connected)return;
        socket=factory.create();
        try {
            socket.connect(new InetSocketAddress(url.getHost(),url.getPort()),getConnectTimeout());socket.setSoTimeout(getReadTimeout());
            OutputStream out=socket.getOutputStream();out.write(("GET "+url.getPath()+" HTTP/1.1\r\nHost: "+url.getHost()+":"+url.getPort()+"\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            BufferedInputStream in=new BufferedInputStream(socket.getInputStream());String status=line(in);
            if(!status.matches("HTTP/1\\.[01] [0-9]{3}.*"))throw new IOException("Invalid HTTP status");
            responseCode=Integer.parseInt(status.substring(9,12));int headers=0;
            for(String header;(header=line(in)).length()>0;) {
                if(++headers>100)throw new IOException("Too many headers");int colon=header.indexOf(':');if(colon<1)throw new IOException("Invalid header");
                String name=header.substring(0,colon).trim(),value=header.substring(colon+1).trim();
                if(name.equalsIgnoreCase("Transfer-Encoding"))throw new IOException("Unsupported transfer encoding");
                if(name.equalsIgnoreCase("Content-Length")){if(length!=-1)throw new IOException("Duplicate content length");try{length=Long.parseLong(value);}catch(NumberFormatException e){throw new IOException("Invalid length");}}
            }
            if(length<0)throw new IOException("Missing Content-Length");
            body=new FilterInputStream(in) {
                long remaining=length;
                public int read()throws IOException{if(remaining==0)return -1;int b=super.read();if(b<0)throw new EOFException();remaining--;return b;}
                public int read(byte[] b,int offset,int size)throws IOException{if(size==0)return 0;if(remaining==0)return -1;int n=in.read(b,offset,(int)Math.min(size,remaining));if(n<0)throw new EOFException();remaining-=n;return n;}
            };connected=true;
        }catch(IOException e){disconnect();throw e;}
    }
    private static String line(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){if(b=='\n')return out.toString(StandardCharsets.US_ASCII.name()).replaceFirst("\r$","");if(out.size()>=8192)throw new IOException("Header too long");out.write(b);}throw new EOFException();
    }
    @Override public int getResponseCode()throws IOException{connect();return responseCode;}
    @Override public long getContentLengthLong(){try{connect();return length;}catch(IOException e){return -1;}}
    @Override public InputStream getInputStream()throws IOException{connect();if(responseCode!=200)throw new IOException("Mothman HTTP "+responseCode);return body;}
    @Override public void disconnect(){if(socket!=null)try{socket.close();}catch(IOException ignored){}connected=false;}
    @Override public boolean usingProxy(){return false;}
}
