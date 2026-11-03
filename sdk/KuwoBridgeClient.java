package com.cabindeck.integration.kuwo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** API v3; request() remains v1/v2-compatible. Callbacks run once on the main looper. */
public final class KuwoBridgeClient implements AutoCloseable {
    public static final String AUTHORITY="cn.kuwo.kwmusiccar.bridge";
    private static final Uri ROOT=Uri.parse("content://"+AUTHORITY);
    private final ContentResolver resolver;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(64));
    private final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<String,Pending>();
    private ContentObserver observer;
    private volatile boolean closed;
    public interface Callback {void onResult(Bundle result);}
    public interface Listener {
        void onStateChanged();
        void onLyricsChanged();
        default void onArtworkChanged(){onStateChanged();}
        default void onListsChanged(){onStateChanged();}
    }
    public KuwoBridgeClient(Context context){resolver=context.getApplicationContext().getContentResolver();}
    private final class Pending {
        final String id;final Callback callback;
        final Runnable timeout=new Runnable(){public void run(){complete(error("TIMEOUT",id));}};
        Pending(String id,Callback cb){this.id=id;callback=cb;}
        void complete(Bundle result){
            if(!pending.remove(id,this))return;
            main.removeCallbacks(timeout);
            Bundle value=result==null?error("EMPTY_RESPONSE",id):new Bundle(result);
            if(value.getString("requestId","").isEmpty())value.putString("requestId",id);
            deliver(callback,value);
        }
    }
    public synchronized String request(final String method,Bundle params,Callback callback){
        final String id=UUID.randomUUID().toString();
        if(closed){deliver(callback,error("CLOSED",id));return id;}
        final Pending call=new Pending(id,callback);pending.put(id,call);main.postDelayed(call.timeout,25000);
        final Bundle request=params==null?new Bundle():new Bundle(params);request.putString("requestId",id);
        request.putParcelable("callback",new ResultReceiver(main){protected void onReceiveResult(int code,Bundle data){call.complete(data);}});
        try{worker.execute(new Runnable(){public void run(){
            if(!pending.containsKey(id))return;
            try{
                Bundle ack=resolver.call(ROOT,method,null,request);
                if(ack==null)call.complete(error("UNAVAILABLE",id));
                else if(!"ACCEPTED".equals(ack.getString("status")))call.complete(ack);
            }catch(SecurityException e){call.complete(error("FORBIDDEN",id));}
            catch(RuntimeException e){call.complete(error("UNAVAILABLE",id));}
        }});}catch(RejectedExecutionException e){call.complete(error(closed?"CLOSED":"BUSY",id));}
        return id;
    }
    public synchronized void observe(final Listener listener){
        if(closed)throw new IllegalStateException("closed");
        if(observer!=null)resolver.unregisterContentObserver(observer);
        observer=new ContentObserver(main){public void onChange(boolean selfChange,Uri uri){
            if(closed || listener==null)return;
            String path=uri==null?"":uri.getPath();
            if("/lyrics".equals(path))listener.onLyricsChanged();
            else if("/artwork".equals(path))listener.onArtworkChanged();
            else if("/lists".equals(path))listener.onListsChanged();
            else listener.onStateChanged();
        }};
        resolver.registerContentObserver(ROOT,true,observer);
    }
    /** Blocking image read; use the client's image/IO executor, never its UI thread. */
    public Bitmap readArtwork(String value,int size)throws IOException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Artwork IO must run off the main thread");
        if(closed)throw new IOException("closed");
        Uri uri=Uri.parse(value);
        if(!"content".equals(uri.getScheme())||!AUTHORITY.equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!"artwork".equals(uri.getPathSegments().get(0))||size<64||size>1024)throw new IOException("Invalid artwork URI or size");
        uri=uri.buildUpon().clearQuery().appendQueryParameter("size",Integer.toString(size)).build();
        try(InputStream stream=resolver.openInputStream(uri)){
            if(stream==null)throw new IOException("Artwork unavailable");
            Bitmap bitmap=BitmapFactory.decodeStream(stream);if(bitmap==null)throw new IOException("Artwork expired or decode failed");return bitmap;
        }
    }
    public static Bundle currentSong(String trackKey){Bundle b=new Bundle();if(trackKey!=null)b.putString("trackKey",trackKey);return b;}
    public static Bundle listItem(String listId,String revision,int index){Bundle b=new Bundle();b.putString("listId",listId);b.putString("revision",revision);b.putInt("index",index);return b;}
    public static Bundle resultItem(String requestId,int index){Bundle b=new Bundle();b.putString("resultRequestId",requestId);b.putInt("index",index);return b;}
    public String initialize(Callback cb){return request("initialize",null,cb);}
    public String getState(Callback cb){return request("getState",null,cb);}
    public String getArtwork(Callback cb){return request("getArtwork",null,cb);}
    public String getCapabilities(Callback cb){return request("capabilities",null,cb);}
    public String setAutoStart(boolean enabled,Callback cb){Bundle b=new Bundle();b.putBoolean("autoStartEnabled",enabled);return request("setStartupConfig",b,cb);}
    public String setFavorite(Bundle songRef,boolean favorite,Callback cb){Bundle b=new Bundle();b.putBundle("songRef",songRef);b.putBoolean("favorite",favorite);return request("setFavorite",b,cb);}
    public String cancel(String targetRequestId,Callback cb){Bundle b=new Bundle();b.putString("targetRequestId",targetRequestId);return request("cancelRequest",b,cb);}
    private void deliver(final Callback callback,final Bundle value){if(callback!=null)main.post(new Runnable(){public void run(){callback.onResult(value);}});}
    private static Bundle error(String status,String id){Bundle b=new Bundle();b.putString("status",status);b.putString("requestId",id);return b;}
    public synchronized void close(){
        if(closed)return;closed=true;
        if(observer!=null){resolver.unregisterContentObserver(observer);observer=null;}
        worker.shutdownNow();
        for(Pending p:pending.values())p.complete(error("CLOSED",p.id));
    }
}
