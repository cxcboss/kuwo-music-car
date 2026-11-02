package cn.kuwo.bridge;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local metadata bitmap only. No arbitrary files, remote downloads or Binder bitmap payloads. */
final class Artwork {
    static final Uri CHANGES=Uri.parse("content://"+BridgeProvider.AUTHORITY+"/artwork");
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4));
    private static volatile Snapshot current;
    private static final String SESSION=java.util.UUID.randomUUID().toString();
    private static long sequence;
    private static final class Snapshot {
        final String trackKey;final Bitmap image;final long revision;
        Snapshot(String key,Bitmap bitmap,long version) { trackKey=key;image=bitmap;revision=version; }
    }
    static void metadata(Object value) {
        try {
            Object music=BridgeProvider.invoke(BridgeProvider.manager(),"v0");
            if(value==null || music==null) {clear();return;}
            String id=String.valueOf(BridgeProvider.invoke(value,"h",new Class<?>[]{String.class},"android.media.metadata.MEDIA_ID"));
            if(!BridgeProvider.field(music,"f").equals(id)) return;
            Bitmap bitmap=(Bitmap)BridgeProvider.invoke(value,"c",new Class<?>[]{String.class},"android.media.metadata.ALBUM_ART");
            if(bitmap==null) bitmap=(Bitmap)BridgeProvider.invoke(value,"c",new Class<?>[]{String.class},"android.media.metadata.ART");
            if(bitmap==null || bitmap.isRecycled()){clear();return;}
            String key=BridgeProvider.track();Snapshot previous=current;
            if(previous!=null && previous.image==bitmap && previous.trackKey.equals(key))return;
            current=new Snapshot(key,bitmap,++sequence);
            BridgeProvider.notifyUri(CHANGES);
        }catch(Exception ignored){clear();}
    }
    private static void clear(){if(current!=null){current=null;BridgeProvider.notifyUri(CHANGES);}}
    static String uri(String trackKey) {
        Snapshot s=current;
        return s!=null && !s.image.isRecycled() && s.trackKey.equals(trackKey)?CHANGES.buildUpon().appendPath(SESSION+"-"+s.revision).build().toString():"";
    }
    static Bundle info() throws Exception {
        Bundle b=new Bundle();String key=BridgeProvider.track();String uri=uri(key);Snapshot s=current;
        b.putString("trackKey",key);b.putString("artworkUri",uri);b.putString("mimeType","image/png");
        Object music=BridgeProvider.invoke(BridgeProvider.manager(),"v0");
        b.putString("coverUrl",music==null?"":MusicApi.http(BridgeProvider.field(music,"v")));
        b.putBoolean("available",!uri.isEmpty());
        if(!uri.isEmpty() && s!=null){b.putLong("revision",s.revision);b.putInt("width",s.image.getWidth());b.putInt("height",s.image.getHeight());}
        return b;
    }
    static ParcelFileDescriptor open(final Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode) || !BridgeProvider.AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size()!=2 ||
                !"artwork".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("Read-only artwork URI required");
        final Snapshot s=current;
        if(s==null || s.image.isRecycled() || !(SESSION+"-"+s.revision).equals(uri.getLastPathSegment())) throw new FileNotFoundException("Artwork expired");
        final int edge;
        try { String size=uri.getQueryParameter("size");edge=size==null?512:Integer.parseInt(size); }
        catch(RuntimeException e) {throw new FileNotFoundException("Invalid size");}
        if(edge<64 || edge>1024)throw new FileNotFoundException("size must be 64..1024");
        try {
            final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            try {IO.execute(new Runnable(){public void run(){
                Bitmap scaled=null;
                try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                    int w=s.image.getWidth(),h=s.image.getHeight();float scale=Math.min(1f,(float)edge/Math.max(w,h));
                    scaled=scale<1f?Bitmap.createScaledBitmap(s.image,Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true):s.image;
                    scaled.compress(Bitmap.CompressFormat.PNG,100,out);
                }catch(Exception ignored){}finally{if(scaled!=null && scaled!=s.image)scaled.recycle();}
            }});}catch(RuntimeException e){pipe[0].close();pipe[1].close();throw new FileNotFoundException("Artwork busy");}
            return pipe[0];
        }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
}
