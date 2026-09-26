package ru.lighthouse.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Mothman V1 transport. Discovery is not authentication: installation always needs consent. */
public final class MothmanUpdates {
    public static final String ID = "lighthouse";
    public static final long INTERVAL_MS = 60L * 60 * 1000;
    public interface Route {
        DatagramSocket socket() throws IOException;
        HttpURLConnection open(URL url) throws IOException;
        List<InetAddress> destinations() throws IOException;
    }
    public static final class Offer {
        public final String base, version, notes, sha256;
        public final long size;
        public Offer(String base, String version, String notes, String sha256, long size) {
            this.base=base;this.version=version;this.notes=notes;this.sha256=sha256;this.size=size;
        }
    }
    public static boolean localAddress(InetAddress address) {
        return address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isMulticastAddress()
            && (address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress());
    }
    public static URL endpoint(String base, String file) throws IOException {
        URL url=new URL(base);
        if (!url.getProtocol().equals("http") || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null
            || !url.getHost().matches("[0-9.]+") || !localAddress(InetAddress.getByName(url.getHost()))
            || !url.getPath().equals("/updates/"+ID+"/") || url.getPort()<1 || url.getPort()>65535)
            throw new IOException("Недопустимый локальный адрес Mothman");
        if(!Set.of("update/android-manifest.json","update/android-package.apk","update/manifest.json","update/package.zip").contains(file))
            throw new IOException("Недопустимый ресурс обновления");
        return new URL(url,file);
    }
    public static List<String> discover(Route route) throws IOException {
        Set<String> nodes=new LinkedHashSet<>();
        try(DatagramSocket socket=route.socket()) {
            socket.setBroadcast(true); socket.setSoTimeout(200);
            byte[] request=("MOTHMAN_UPDATE_DISCOVERY_V1|"+ID).getBytes(StandardCharsets.US_ASCII);
            Set<InetAddress> destinations=new LinkedHashSet<>(route.destinations());
            destinations.add(InetAddress.getByName("255.255.255.255")); destinations.add(InetAddress.getByName("239.255.77.77"));
            for(InetAddress target:destinations)try{socket.send(new DatagramPacket(request,request.length,target,8766));}catch(IOException ignored){}
            long deadline=System.nanoTime()+2_000_000_000L;
            while(System.nanoTime()<deadline && nodes.size()<16 && !Thread.currentThread().isInterrupted()) {
                DatagramPacket response=new DatagramPacket(new byte[512],512);
                try { socket.receive(response); } catch(SocketTimeoutException timeout) { continue; }
                String body=new String(response.getData(),0,response.getLength(),StandardCharsets.US_ASCII);
                String[] fields=body.split("\\|",-1);
                if(fields.length!=3 || !fields[0].equals("MOTHMAN_UPDATE_NODE_V1") || !fields[2].equals(ID) || !localAddress(response.getAddress()))continue;
                try { int port=Integer.parseInt(fields[1]); if(port<1||port>65535)continue;
                    nodes.add("http://"+response.getAddress().getHostAddress()+":"+port+"/updates/"+ID+"/");
                } catch(NumberFormatException ignored){}
            }
        }
        return new ArrayList<>(nodes);
    }
    public static Offer fetch(Route route,String base,boolean android) throws IOException {
        HttpURLConnection connection=route.open(endpoint(base,android?"update/android-manifest.json":"update/manifest.json"));
        configure(connection,2500);
        // First Windows manifest may wait while Mothman prepares its cached ZIP.
        connection.setReadTimeout(android ? 10000 : 60000);
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Mothman: манифест недоступен");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException("Манифест слишком большой");bytes.write(buffer,0,n);}}
            return parse(base,new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }finally{connection.disconnect();}
    }
    public static Offer parse(String base,String json) throws IOException {
        Map<String,String> fields=flatJson(json);
        if(!"1".equals(fields.get("FormatVersion")))throw new IOException("Неизвестный формат обновления");
        // No public trust key is provisioned yet. Never silently ignore a claimed signature.
        if(fields.containsKey("Signature") || fields.containsKey("SignatureAlgorithm") || fields.containsKey("KeyId"))
            throw new IOException("Подпись манифеста не проверена: доверенный ключ не настроен");
        String version=fields.getOrDefault("Version",""); versionParts(version);
        String sha=fields.getOrDefault("Sha256","").toLowerCase(Locale.ROOT);
        long size;try{size=Long.parseLong(fields.getOrDefault("PackageSize","0"));}catch(NumberFormatException e){throw new IOException("Неверный размер",e);}
        if(!sha.matches("[0-9a-f]{64}")||size<=0||size>2L*1024*1024*1024)throw new IOException("Неверная контрольная сумма/размер");
        return new Offer(base,version,fields.getOrDefault("Notes",""),sha,size);
    }
    public static void download(Route route,Offer offer,boolean android,Path destination) throws Exception {
        HttpURLConnection c=route.open(endpoint(offer.base,android?"update/android-package.apk":"update/package.zip"));configure(c,15000);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try {
            if(c.getResponseCode()!=200)throw new IOException("Пакет Mothman недоступен");
            long length=c.getContentLengthLong();if(length>=0&&length!=offer.size)throw new IOException("Размер HTTP не совпадает с манифестом");
            try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(destination)) {
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1) {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();count+=n;if(count>offer.size)throw new IOException("Размер пакета превышен");digest.update(buffer,0,n);out.write(buffer,0,n);}
            }
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b));
            if(count!=offer.size||!hash.toString().equals(offer.sha256))throw new IOException("Размер или SHA-256 не совпадает");
        }catch(Exception error){Files.deleteIfExists(destination);throw error;}finally{c.disconnect();}
    }
    private static void configure(HttpURLConnection c,int timeout){c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");}
    private static String[] versionParts(String value) throws IOException {
        if(value==null || !value.matches("[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"))throw new IOException("Неверная семантическая версия");
        String[] parts=value.replaceFirst("^[vV]","").split("\\+",2)[0].split("-",2);
        if(parts.length>1)for(String identifier:parts[1].split("\\."))if(identifier.matches("0[0-9]+"))throw new IOException("Неверный prerelease SemVer");
        return parts;
    }
    public static int compareVersions(String left,String right) throws IOException {
        String[] a=versionParts(left),b=versionParts(right),ac=a[0].split("\\."),bc=b[0].split("\\.");
        for(int i=0;i<3;i++){int n=new java.math.BigInteger(ac[i]).compareTo(new java.math.BigInteger(bc[i]));if(n!=0)return n;}
        if(a.length!=b.length)return a.length==1?1:-1;
        if(a.length==1)return 0;
        String[] ap=a[1].split("\\."),bp=b[1].split("\\.");
        for(int i=0;i<Math.min(ap.length,bp.length);i++) {
            boolean an=ap[i].matches("[0-9]+"),bn=bp[i].matches("[0-9]+");
            int n=an&&bn?new java.math.BigInteger(ap[i]).compareTo(new java.math.BigInteger(bp[i])):an!=bn?(an?-1:1):ap[i].compareTo(bp[i]);if(n!=0)return n;
        }
        return Integer.compare(ap.length,bp.length);
    }
    /** Strict flat JSON object parser for the documented V1 manifest; duplicates are rejected. */
    private static Map<String,String> flatJson(String text) throws IOException {
        Map<String,String> out=new LinkedHashMap<>();int[] at={0};space(text,at);expect(text,at,'{');space(text,at);
        while(at[0]<text.length()&&text.charAt(at[0])!='}') {
            String key=quoted(text,at);space(text,at);expect(text,at,':');space(text,at);String value;
            if(at[0]<text.length()&&text.charAt(at[0])=='"')value=quoted(text,at);
            else {int start=at[0];while(at[0]<text.length()&&"-0123456789".indexOf(text.charAt(at[0]))>=0)at[0]++;value=text.substring(start,at[0]);if(!value.matches("-?(0|[1-9][0-9]*)"))throw new IOException("Неверное JSON значение");}
            if(out.put(key,value)!=null)throw new IOException("Повтор поля JSON");space(text,at);
            if(at[0]<text.length()&&text.charAt(at[0])=='}')break;expect(text,at,',');space(text,at);
            if(at[0]>=text.length()||text.charAt(at[0])=='}')throw new IOException("Неверный JSON");
        }
        expect(text,at,'}');space(text,at);if(at[0]!=text.length())throw new IOException("Лишние данные JSON");return out;
    }
    private static void space(String s,int[] p){while(p[0]<s.length()&&Character.isWhitespace(s.charAt(p[0])))p[0]++;}
    private static void expect(String s,int[] p,char c)throws IOException{if(p[0]>=s.length()||s.charAt(p[0]++)!=c)throw new IOException("Неверный JSON");}
    private static String quoted(String s,int[] p)throws IOException {
        expect(s,p,'"');StringBuilder b=new StringBuilder();
        while(p[0]<s.length()){char c=s.charAt(p[0]++);if(c=='"')return b.toString();if(c<32)throw new IOException("Неверная строка JSON");
            if(c=='\\'){if(p[0]>=s.length())throw new IOException("Неверная строка JSON");char e=s.charAt(p[0]++);
                switch(e){case '"','\\','/'->b.append(e);case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'b'->b.append('\b');case 'f'->b.append('\f');case 'u'->{try{b.append((char)Integer.parseInt(s.substring(p[0],p[0]+4),16));p[0]+=4;}catch(RuntimeException ex){throw new IOException("Неверный Unicode",ex);}}default->throw new IOException("Неверная escape-последовательность");}
            }else b.append(c);
        }throw new IOException("Незакрытая строка JSON");
    }
}
