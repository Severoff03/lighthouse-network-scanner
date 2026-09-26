package ru.lighthouse.desktop;

import ru.lighthouse.core.*;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

/** User-confirmed GitHub updates with local Mothman fallback. */
final class MothmanDesktop {
    private final JFrame owner;
    private final String version;
    private final BooleanSupplier scanning;
    private final Preferences prefs=Preferences.userNodeForPackage(MothmanDesktop.class);
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mothman-update");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private String announced="";
    private volatile long lastCheck;
    private String networkState="";
    private final Path state=Path.of(System.getProperty("user.home"),".lighthouse");
    MothmanDesktop(JFrame owner,String version,BooleanSupplier scanning){this.owner=owner;this.version=version;this.scanning=scanning;}
    void start(){if(isWindows()) {
        worker.scheduleWithFixedDelay(()->check(false),3,3600,TimeUnit.SECONDS);
        // Observe local interface state only; no network packets or wake locks for this watcher.
        worker.scheduleWithFixedDelay(()->{try {
            List<String> active=new ArrayList<>();
            for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces()))if(nic.isUp()&&!nic.isLoopback())
                for(InetAddress ip:Collections.list(nic.getInetAddresses()))if(ip instanceof Inet4Address)active.add(nic.getName()+":"+ip.getHostAddress());
            Collections.sort(active);String state=active.toString();
            if(!state.equals(networkState)){networkState=state;if(!active.isEmpty())check(false);}
        }catch(Exception ignored){}},30,60,TimeUnit.SECONDS);
    }}
    void close(){worker.shutdownNow();}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
    void check(boolean manual){
        if(!isWindows()){if(manual)message("Mothman-пакет обновления доступен для Windows.");return;}
        if(!manual && System.currentTimeMillis()-lastCheck<MothmanUpdates.INTERVAL_MS)return;
        if(!busy.compareAndSet(false,true))return;
        lastCheck=System.currentTimeMillis();
        worker.execute(()->{try{
            try {
                GitHubDesktopUpdates.Offer github = GitHubDesktopUpdates.latest();
                if (MothmanUpdates.compareVersions(github.version(), version) <= 0) {
                    if (manual) message("Новой версии нет.");
                    return;
                }
                if (manual || !announced.equals(github.version())) {
                    announced = github.version(); SwingUtilities.invokeLater(() -> prompt(github));
                }
                return;
            } catch (Exception githubError) { log("GitHub check: " + githubError.getMessage()); }
            List<MothmanUpdates.Route> routes=routes();MothmanUpdates.Offer found=null;MothmanUpdates.Route selected=null;
            String saved=prefs.get("base","");
            for(MothmanUpdates.Route route:routes)if(!saved.isEmpty())try{found=MothmanUpdates.fetch(route,saved,false);selected=route;break;}catch(IOException ignored){}
            if(found==null)outer:for(MothmanUpdates.Route route:routes)for(String base:MothmanUpdates.discover(route))try{found=MothmanUpdates.fetch(route,base,false);selected=route;break outer;}catch(IOException ignored){}
            if(found==null)throw new IOException("Mothman не найден в локальной сети");
            prefs.put("base",found.base);log("manifest "+found.version);
            if(MothmanUpdates.compareVersions(found.version,version)<=0){if(manual)message("Новой версии нет.");return;}
            final MothmanUpdates.Offer offer=found;final MothmanUpdates.Route route=selected;
            if(manual||!announced.equals(offer.version)){announced=offer.version;SwingUtilities.invokeLater(()->prompt(offer,route));}
        }catch(Exception e){log("check: "+e.getMessage());if(manual)message(e.getMessage());}finally{busy.set(false);}});
    }
    private void prompt(MothmanUpdates.Offer offer,MothmanUpdates.Route route){
        String notes=offer.notes.length()>900?offer.notes.substring(0,900)+"…":offer.notes;
        JTextArea text=new JTextArea("Lighthouse "+offer.version+"\nИсточник: "+offer.base+"\n\n"+notes
            +"\n\nМанифест без доверенной цифровой подписи. Устанавливайте только с вашего сервера. После проверки пакета Lighthouse закроется и перезапустится.");
        text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setColumns(52);text.setRows(12);
        if(JOptionPane.showConfirmDialog(owner,new JScrollPane(text),"Обновление Mothman",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        if(scanning.getAsBoolean()){message("Сначала завершите сканирование.");return;}
        installWindowsPackage(offer.version, zip -> MothmanUpdates.download(route,offer,false,zip));
    }
    private void prompt(GitHubDesktopUpdates.Offer offer) {
        String notes = offer.notes().length() > 900 ? offer.notes().substring(0, 900) + "…" : offer.notes();
        JTextArea text = new JTextArea("Lighthouse " + offer.version() + "\nИсточник: GitHub Releases\n\n" + notes
            + "\n\nФайл проверяется по SHA-256 GitHub. После подтверждения Lighthouse закроется и перезапустится.");
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setColumns(52); text.setRows(12);
        if (JOptionPane.showConfirmDialog(owner, new JScrollPane(text), "Обновление Lighthouse",
            JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        if (scanning.getAsBoolean()) { message("Сначала завершите сканирование."); return; }
        installWindowsPackage(offer.version(), zip -> GitHubDesktopUpdates.download(offer, zip));
    }
    @FunctionalInterface private interface PackageDownload { void download(Path zip) throws Exception; }
    private void installWindowsPackage(String targetVersion, PackageDownload downloader) {
        if(!busy.compareAndSet(false,true))return;
        worker.execute(()->{try{
            String launcher=System.getProperty("jpackage.app-path","");
            if(launcher.isBlank())throw new IOException("Обновление устанавливается из Windows-сборки Lighthouse.exe");
            Path exe=Path.of(launcher).toAbsolutePath().normalize(),install=exe.getParent();
            if(!exe.getFileName().toString().equalsIgnoreCase("Lighthouse.exe")||!Files.isDirectory(install.resolve("app"))||!Files.isDirectory(install.resolve("runtime")))throw new IOException("Неизвестная структура установки");
            Files.createDirectories(state);Path temp=Files.createTempDirectory(state,"update-");Path zip=temp.resolve("package.zip");
            downloader.download(zip);Path unpacked=temp.resolve("unpacked");unpack(zip,unpacked);
            final Path unpack = !Files.isRegularFile(unpacked.resolve("Lighthouse.exe"))
                && Files.isRegularFile(unpacked.resolve("Lighthouse").resolve("Lighthouse.exe"))
                    ? unpacked.resolve("Lighthouse") : unpacked;
            if(!Files.isRegularFile(unpack.resolve("Lighthouse.exe"))||!Files.isDirectory(unpack.resolve("runtime"))||!Files.isDirectory(unpack.resolve("app")))throw new IOException("Неполный пакет Windows");
            Path script=temp.resolve("install.ps1");
            try(InputStream in=MothmanDesktop.class.getResourceAsStream("/mothman-install.ps1")){if(in==null)throw new IOException("Updater script missing");Files.copy(in,script);}
            // Final check and launch share the UI event queue with starting scans/report saves.
            SwingUtilities.invokeAndWait(()->{
                if(scanning.getAsBoolean()){message("Сначала завершите сканирование или сохранение отчёта; затем повторите обновление.");return;}
                try {
                    new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-ExecutionPolicy","Bypass","-File",script.toString(),
                        "-ParentId",Long.toString(ProcessHandle.current().pid()),"-Install",install.toString(),"-Stage",unpack.toString(),"-Log",state.resolve("updates.log").toString())
                        .redirectErrorStream(true).redirectOutput(temp.resolve("updater-output.log").toFile()).start();
                    log("verified package "+targetVersion+"; updater started");
                    owner.dispose();System.exit(0);
                }catch(IOException e){throw new java.io.UncheckedIOException(e);}
            });
        }catch(Exception e){log("update rejected: "+e.getMessage());message(e.getMessage());}finally{busy.set(false);}});
    }
    static void unpack(Path zip,Path destination)throws IOException {
        Files.createDirectories(destination);long total=0;int entries=0;Set<Path> names=new HashSet<>();
        try(java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(Files.newInputStream(zip))){java.util.zip.ZipEntry entry;
            while((entry=in.getNextEntry())!=null){
                if(++entries>30000)throw new IOException("Слишком много файлов ZIP");String name=entry.getName().replace('\\','/');
                if(name.startsWith("/")||name.contains(":")||Arrays.stream(name.split("/")).anyMatch(p->p.equals("..")||p.endsWith(".")||p.endsWith(" ")||p.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")))throw new IOException("Опасный путь ZIP");
                Path target=destination.resolve(name).normalize();if(!target.startsWith(destination)||!names.add(target))throw new IOException("Неверный путь ZIP");
                if(entry.isDirectory()){Files.createDirectories(target);continue;}Files.createDirectories(target.getParent());
                try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>4L*1024*1024*1024)throw new IOException("ZIP слишком большой");out.write(b,0,n);}}
            }
        }
    }
    static List<MothmanUpdates.Route> routes()throws Exception {
        // OS-selected physical adapters, rather than the VPN's public default route.
        String command="Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | ForEach-Object { Get-NetIPAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty IPAddress }";
        Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",command).redirectErrorStream(true).start();
        if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Тайм-аут списка сетевых адаптеров");}
        List<MothmanUpdates.Route> result=new ArrayList<>();
        for(String line:new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
            String ip=line.trim();if(!ip.matches("[0-9.]+"))continue;InetAddress local=InetAddress.getByName(ip);if(!MothmanUpdates.localAddress(local))continue;
            result.add(new MothmanUpdates.Route(){
                public DatagramSocket socket()throws IOException{
                    MulticastSocket socket=new MulticastSocket(null);
                    try{socket.bind(new InetSocketAddress(local,0));NetworkInterface nic=NetworkInterface.getByInetAddress(local);if(nic!=null)socket.setNetworkInterface(nic);return socket;}
                    catch(IOException e){socket.close();throw e;}
                }
                public HttpURLConnection open(URL url)throws IOException{return new LocalUpdateConnection(url,()->{Socket s=new Socket();s.bind(new InetSocketAddress(local,0));return s;});}
                public List<InetAddress> destinations()throws IOException {List<InetAddress> all=new ArrayList<>();NetworkInterface nic=NetworkInterface.getByInetAddress(local);if(nic!=null)for(InterfaceAddress a:nic.getInterfaceAddresses())if(a.getBroadcast()!=null)all.add(a.getBroadcast());return all;}
            });
        }return result;
    }
    private void message(String text){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(owner,text,"Обновления Lighthouse",JOptionPane.INFORMATION_MESSAGE));}
    private void log(String text){try{Files.createDirectories(state);Files.writeString(state.resolve("updates.log"),java.time.Instant.now()+" "+text.replace('\n',' ')+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException ignored){}}
}
