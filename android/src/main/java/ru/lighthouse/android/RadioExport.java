package ru.lighthouse.android;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.zip.*;

/** Exports only the completed monitoring session into public Downloads. */
final class RadioExport {
    private RadioExport(){}
    static String save(Context context,File session)throws IOException {
        if(session==null||!session.isDirectory())throw new IOException("No monitoring session");
        String name="Lighthouse-radio-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())+".zip";
        if(Build.VERSION.SDK_INT>=29){
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,"application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new IOException("Downloads unavailable");
            try{try(OutputStream out=context.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Downloads stream unavailable");zip(session,out);}
                ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(context.getContentResolver().update(uri,ready,null,null)!=1)throw new IOException("Could not publish ZIP");
            }catch(Exception error){context.getContentResolver().delete(uri,null,null);throw error instanceof IOException?(IOException)error:new IOException(error);}
        }else{
            if(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
                throw new IOException("Downloads permission missing on Android 8/9");
            File download=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);if(!download.isDirectory()&&!download.mkdirs())throw new IOException("Downloads unavailable");
            File target=new File(download,name);try(OutputStream out=new FileOutputStream(target)){zip(session,out);}catch(IOException e){target.delete();throw e;}
        }return name;
    }
    private static void zip(File session,OutputStream output)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(output)){
            try(java.util.stream.Stream<Path> paths=Files.walk(session.toPath())){
                for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){
                    zip.putNextEntry(new ZipEntry(session.toPath().relativize(path).toString().replace('\\','/')));
                    Files.copy(path,zip);zip.closeEntry();
                }
            }
        }
    }
}
