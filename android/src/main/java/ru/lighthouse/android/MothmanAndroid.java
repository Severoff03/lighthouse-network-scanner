package ru.lighthouse.android;

import android.content.Context;
import android.net.*;
import ru.lighthouse.core.MothmanUpdates;
import java.io.*;
import java.net.*;
import java.util.*;

final class MothmanAndroid {
    private final Context app;
    private final Map<String,MothmanUpdates.Route> routes=new HashMap<>();
    MothmanAndroid(Context app){this.app=app;}
    private List<MothmanUpdates.Route> networks() {
        List<MothmanUpdates.Route> result=new ArrayList<>();
        ConnectivityManager manager=app.getSystemService(ConnectivityManager.class);
        if(manager==null)return result;
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
            LinkProperties links=manager.getLinkProperties(network);
            result.add(new MothmanUpdates.Route(){
                public DatagramSocket socket()throws IOException {DatagramSocket s=new DatagramSocket(null);try{network.bindSocket(s);s.bind(new InetSocketAddress(0));return s;}catch(IOException e){s.close();throw e;}}
                // Bind only updater requests. Binding the whole process would corrupt network diagnostics.
                public HttpURLConnection open(URL url)throws IOException{return new ru.lighthouse.core.LocalUpdateConnection(url, () -> { Socket socket=new Socket(); try { network.bindSocket(socket); return socket; } catch(IOException e) { socket.close(); throw e; } });}
                public List<InetAddress> destinations()throws IOException {
                    List<InetAddress> out=new ArrayList<>();
                    if(links!=null)for(LinkAddress address:links.getLinkAddresses())if(address.getAddress() instanceof Inet4Address && address.getPrefixLength()<31){
                        byte[] ip=address.getAddress().getAddress();int prefix=address.getPrefixLength();
                        for(int bit=prefix;bit<32;bit++)ip[bit/8]|=(byte)(1<<(7-bit%8));out.add(InetAddress.getByAddress(ip));
                    }return out;
                }
            });
        }return result;
    }
    MothmanUpdates.Offer check()throws Exception {
        List<MothmanUpdates.Route> available=networks();
        String saved=app.getSharedPreferences("mothman-update",0).getString("base","");
        for(MothmanUpdates.Route route:available)if(!saved.isEmpty())try{return fetch(route,saved);}catch(IOException ignored){}
        for(MothmanUpdates.Route route:available) {
            List<String> nodes;try{nodes=MothmanUpdates.discover(route);}catch(IOException ignored){continue;}
            for(String base:nodes)try{return fetch(route,base);}catch(IOException ignored){}
        }
        throw new IOException("Mothman не найден в локальной сети");
    }
    private MothmanUpdates.Offer fetch(MothmanUpdates.Route route,String base)throws IOException {
        MothmanUpdates.Offer offer=MothmanUpdates.fetch(route,base,true);routes.put(base,route);
        app.getSharedPreferences("mothman-update",0).edit().putString("base",base).apply();return offer;
    }
    void download(UpdateManager.UpdateInfo update,File file)throws Exception {
        String suffix="update/android-package.apk";
        if(!update.assetUrl.endsWith(suffix))throw new IOException("Неверный URL Mothman");
        String base=update.assetUrl.substring(0,update.assetUrl.length()-suffix.length());
        MothmanUpdates.Route route=routes.get(base);
        MothmanUpdates.Offer current=null;
        if(route!=null)try{current=fetch(route,base);}catch(IOException ignored){}
        if(current==null)for(MothmanUpdates.Route candidate:networks())try{current=fetch(candidate,base);route=candidate;break;}catch(IOException ignored){}
        if(current==null)throw new IOException("Mothman недоступен");
        if(!current.version.equals(update.version)||!current.sha256.equals(update.sha256)||current.size!=update.sizeBytes)
            throw new IOException("Выпуск изменился: проверьте обновления повторно");
        MothmanUpdates.download(route,current,true,file.toPath());
    }
    void log(String event){
        synchronized(MothmanAndroid.class){try{
            File file=new File(app.getNoBackupFilesDir(),"updates.log");
            if(file.length()>512*1024)java.nio.file.Files.move(file.toPath(),new File(app.getNoBackupFilesDir(),"updates.previous.log").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try(java.io.Writer out=new java.io.OutputStreamWriter(new FileOutputStream(file,true),java.nio.charset.StandardCharsets.UTF_8)){out.write(java.time.Instant.now()+" "+event.replace('\n',' ') +"\n");}
        }catch(IOException ignored){}}
    }
}
