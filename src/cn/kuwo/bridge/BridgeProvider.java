package cn.kuwo.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned, bounded local music API. All player operations run on its main thread. */
public final class BridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "cn.kuwo.kwmusiccar.bridge";
    public static final Uri STATE = Uri.parse("content://" + AUTHORITY + "/state");
    public static final Uri LYRICS = Uri.parse("content://" + AUTHORITY + "/lyrics");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static String lyricJson = "";
    private static String lyricTrack = "";
    private static long lyricRevision;
    private static long stateRevision;
    private static boolean stateNotificationPending;
    private static boolean listsNotificationPending;
    private final Set<String> allowed = new HashSet<String>();
    private final Map<String, Search> searches = new HashMap<String, Search>();
    private final Map<String, Saved> saved = new HashMap<String, Saved>();
    private int queued;

    @Override public boolean onCreate() {
        context = getContext();
        // Application providers initialize before KwApp. Do not touch player singletons here.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("bridge_clients.txt"), "UTF-8"))) {
            for (String line; (line = r.readLine()) != null;) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) allowed.add(line);
            }
        } catch (Exception e) { Log.e("KwBridge", "Cannot load caller list", e); }
        return true;
    }

    private boolean authorized(int uid) {
        if (uid == Process.myUid()) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) if (allowed.contains(name)) return true;
        return false;
    }

    @Override public Bundle call(final String method, String arg, Bundle input) {
        final int uid = Binder.getCallingUid();
        if (!authorized(uid)) return reply("FORBIDDEN", "");
        if ("capabilities".equals(method)) {
            Bundle b = reply("OK", "");
            b.putInt("apiVersion", 3);
            b.putInt("minimumCompatibleApiVersion", 1);
            String[] original = new String[] {"getState", "getLyrics", "getLists",
                "getListItems", "playItem", "play", "pause", "stop", "previous", "next",
                "seek", "setMode", "search", "cancelSearch", "playSearchItem"};
            ArrayList<String> methods = new ArrayList<String>(java.util.Arrays.asList(original));
            methods.addAll(java.util.Arrays.asList(MusicApi.METHODS));
            b.putStringArray("methods", methods.toArray(new String[0]));
            b.putStringArray("listTypes", new String[] {"queue", "favorites", "history", "loadedPlaylists"});
            b.putStringArray("observerUris", new String[] {STATE.toString(),LYRICS.toString(),Artwork.CHANGES.toString(),MusicApi.LISTS.toString()});
            b.putInt("maxPageSize",50);b.putInt("requestTimeoutMs",20000);b.putInt("resultRetentionMs",120000);
            b.putString("artworkMimeType","image/png");b.putInt("maxArtworkEdge",1024);
            b.putString("wordLyricFormat","LRCX");b.putInt("maxWordPageSize",50);
            return b;
        }
        final Bundle request;
        final ResultReceiver callback;
        final String id;
        try {
            request = input == null ? new Bundle() : new Bundle(input);
            request.setClassLoader(ResultReceiver.class.getClassLoader());
            id = request.getString("requestId", "");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) return reply("INVALID_REQUEST_ID", "");
            callback = request.getParcelable("callback");
            if (callback == null) return reply("CALLBACK_REQUIRED", id);
            request.remove("callback");
        } catch (RuntimeException e) { return reply("INVALID_ARGUMENT", ""); }
        synchronized (this) {
            if (queued >= 32) return reply("BUSY", id);
            queued++;
        }
        MAIN.post(new Runnable() {
            @Override public void run() {
                synchronized (BridgeProvider.this) { queued--; }
                try { dispatch(method, request, uid, id, callback); }
                catch (ClassCastException e) { send(callback, reply("INVALID_ARGUMENT", id)); }
                catch (IllegalArgumentException e) { send(callback, reply("INVALID_ARGUMENT", id)); }
                catch (Throwable e) {
                    Log.w("KwBridge", method + " failed", e);
                    send(callback, reply("NOT_READY", id));
                }
            }
        });
        return reply("ACCEPTED", id);
    }

    private void dispatch(String method, Bundle in, int uid, String id, ResultReceiver cb) throws Exception {
        cleanup();
        if (MusicApi.early(method)) { MusicApi.dispatch(this,context,method,in,uid,id,cb);return; }
        if (!"cancelSearch".equals(method)) {
            Object application = staticCall("cn.kuwo.kwmusiccar.KwApp", "getInstance");
            if (application == null || !(Boolean) invoke(application, "isAppPrivacyAgreed")) {
                send(cb, reply("PRIVACY_REQUIRED", id)); return;
            }
            if (!(Boolean) staticCall("cn.kuwo.service.MainService", "q")) {
                send(cb, reply("NOT_READY", id)); return;
            }
        }
        if (MusicApi.handles(method)) { MusicApi.dispatch(this,context,method,in,uid,id,cb);return; }
        Bundle out = reply("OK", id);
        if ("play".equals(method) || "pause".equals(method) || "stop".equals(method) || "previous".equals(method)
                || "next".equals(method) || "seek".equals(method) || "playItem".equals(method) || "playSearchItem".equals(method))
            PerformanceHooks.playbackCommand();
        if ("getState".equals(method)) out.putBundle("state", state());
        else if ("getLyrics".equals(method)) lyrics(in, out);
        else if ("getLists".equals(method)) lists(in, out);
        else if ("getListItems".equals(method)) {
            List<?> items = list(in.getString("listId", "queue"));
            out.putString("revision", revision(items));
            page(items, in, out, 0);
        } else if ("search".equals(method)) { search(in, uid, id, cb); return; }
        else if ("cancelSearch".equals(method)) {
            String target = in.getString("targetRequestId", "");
            Search task = searches.get(uid + ":" + target);
            if (task != null) task.finish(reply("CANCELLED", target), true);
            out.putBoolean("cancelled", task != null);
        } else if ("playSearchItem".equals(method)) {
            Saved entry = saved.get(uid + ":" + in.getString("searchRequestId", ""));
            if (entry == null) { send(cb, reply("STALE_SEARCH", id)); return; }
            int index = in.getInt("index", -1);
            if (index < 0 || index >= entry.items.size()) throw new IllegalArgumentException();
            invoke(controller(), "B0", new Class<?>[] {List.class, int.class}, entry.items, index);
            command(out);
        } else if ("playItem".equals(method)) {
            List<?> items = list(in.getString("listId", "queue"));
            if (!revision(items).equals(in.getString("revision", ""))) {
                send(cb, reply("STALE_LIST", id)); return;
            }
            int index = in.getInt("index", -1);
            if (index < 0 || index >= items.size()) throw new IllegalArgumentException();
            invoke(controller(), "B0", new Class<?>[] {List.class, int.class}, items, index);
            command(out);
        } else if ("setMode".equals(method)) {
            String mode = in.getString("mode", "");
            String internal;
            if ("single".equals(mode)) internal = "MEDIA_ONE";
            else if ("sequential".equals(mode)) internal = "MEDIA_ORDER";
            else if ("repeat_all".equals(mode)) internal = "MEDIA_CIRCLE";
            else if ("shuffle".equals(mode)) internal = "MEDIA_RANDOM";
            else throw new IllegalArgumentException();
            boolean accepted = (Boolean) invoke(manager(), "i1", new Class<?>[] {String.class}, internal);
            out.putBoolean("accepted", accepted);
            out.putBoolean("stateConfirmed", false);
            if (!accepted) out.putString("status", "UNSUPPORTED_CONTENT");
        } else if ("seek".equals(method)) {
            long position = in.getLong("positionMs", -1);
            int duration = ((Number) invoke(player(), "getDuration")).intValue();
            if (position < 0 || position > Integer.MAX_VALUE || (duration > 0 && position > duration))
                throw new IllegalArgumentException();
            invoke(player(), "seek", new Class<?>[] {int.class}, (int) position);
            command(out);
        } else {
            // Reuse the same media-session callback as Android media buttons.
            Object service = staticCall("cn.kuwo.mod.mediaSession.KwMediaSessionService", "j");
            if (service == null) { send(cb, reply("NOT_READY", id)); return; }
            String callbackName;
            if ("play".equals(method)) callbackName = "i";
            else if ("pause".equals(method)) callbackName = "h";
            else if ("stop".equals(method)) callbackName = "C";
            else if ("previous".equals(method)) callbackName = "A";
            else if ("next".equals(method)) callbackName = "z";
            else { send(cb, reply("UNSUPPORTED", id)); return; }
            Object handler = Class.forName("cn.kuwo.mod.mediaSession.KwMediaSessionService$d")
                .getConstructor(service.getClass()).newInstance(service);
            invoke(handler, callbackName);
            command(out);
        }
        send(cb, out);
    }

    static void command(Bundle b) {
        b.putBoolean("accepted", true);
        b.putBoolean("stateConfirmed", false);
    }

    static Object manager() throws Exception {
        return staticCall("cn.kuwo.mod.playcontrol.PlayerStateManager", "u0");
    }
    static Object player() throws Exception { return staticCall("c6.b", "n"); }
    static Object controller() throws Exception { return staticCall("q5.r0", "A"); }
    static Object staticCall(String cls, String name) throws Exception {
        return ReflectAccess.method(Class.forName(cls), name).invoke(null);
    }
    static Object invoke(Object o, String name) throws Exception {
        return invoke(o, name, new Class<?>[0]);
    }
    static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        return ReflectAccess.method(o.getClass(), name, types).invoke(o, args);
    }
    static String field(Object music, String key) throws Exception {
        Object v = ReflectAccess.field(music.getClass(), key).get(music);
        return v == null ? "" : v.toString();
    }
    static String track() throws Exception {
        Object m = invoke(manager(), "v0");
        return m == null ? "" : field(m, "f") + ":" + field(m, "g") + ":" + field(m, "h");
    }
    static Bundle song(Object m, int index) throws Exception {
        Bundle b = new Bundle();
        b.putInt("index", index);
        if (m == null) return b;
        b.putString("trackId", field(m, "f"));
        b.putString("title", trim(field(m, "g"), 512));
        b.putString("artist", trim(field(m, "h"), 512));
        b.putString("album", trim(field(m, "j"), 512));
        b.putString("artistId",field(m,"i"));b.putString("albumId",field(m,"u"));
        String key=field(m,"f")+":"+field(m,"g")+":"+field(m,"h");
        b.putString("trackKey",key);b.putString("coverUrl",MusicApi.http(field(m,"v")));
        b.putString("artworkUri",Artwork.uri(key));
        b.putLong("durationMs", ((Number) ReflectAccess.field(m.getClass(), "k").get(m)).longValue() * 1000L);
        return b;
    }
    private static String trim(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }
    static Bundle state() throws Exception {
        Object mgr = manager();
        Object state = invoke(mgr, "y0");
        Bundle b = new Bundle();
        b.putString("state", invoke(state, "p").toString());
        b.putInt("contentType", ((Number) invoke(state, "q")).intValue());
        b.putInt("positionMs", ((Number) invoke(mgr, "t0")).intValue());
        b.putInt("durationMs", ((Number) invoke(player(), "getDuration")).intValue());
        int mode = ((Number) invoke(player(), "o1")).intValue();
        b.putString("mode", mode == 0 ? "single" : mode == 1 ? "sequential" : mode == 2 ? "repeat_all" : mode == 3 ? "shuffle" : "unknown");
        b.putBundle("song", song(invoke(mgr, "v0"), -1));
        b.putString("trackKey", track());
        b.putBoolean("playing","PLAYING".equals(b.getString("state")));
        b.putInt("activeQueueIndex",((Number)invoke(player(),"G4")).intValue());
        b.putLong("updatedAtElapsedMs", SystemClock.elapsedRealtime());
        b.putLong("revision", stateRevision);
        return b;
    }
    static List<?> list(String id) throws Exception {
        if (id.length() > 1024) throw new IllegalArgumentException();
        if ("queue".equals(id)) {Object items=invoke(manager(),"w0");return items instanceof List?new ArrayList<Object>((List<?>)items):new ArrayList<Object>();}
        Object mgr = staticCall("c6.b", "j");
        Object result;
        if ("history".equals(id)) result = invoke(mgr,"A4",new Class<?>[]{String.class},"最近播放");
        else if ("favorites".equals(id)) result = invoke(mgr, "A4", new Class<?>[]{String.class}, "我喜欢听");
        else if (id.startsWith("named:")) result = invoke(mgr, "A4", new Class<?>[]{String.class}, Uri.decode(id.substring(6)));
        else throw new IllegalArgumentException();
        if (result != null) {
            String type = invoke(result, "s").toString();
            if (!MusicApi.visibleType(type)) throw new IllegalArgumentException();
        }
        return result == null ? new ArrayList<Object>() : new ArrayList<Object>((List<?>) invoke(result, "z"));
    }
    static String revision(List<?> items) throws Exception {
        long hash = 0xcbf29ce484222325L;
        for (Object m : items) {
            String key = m == null ? "null" : field(m, "f") + "|" + field(m, "g") + "|" + field(m, "h") + "|" + field(m, "O0");
            for (int i = 0; i < key.length(); i++) { hash ^= key.charAt(i); hash *= 0x100000001b3L; }
            hash ^= 0xffff; hash *= 0x100000001b3L;
        }
        return items.size() + "-" + Long.toHexString(hash);
    }
    static int pageSize(Bundle b) {
        int size = b.getInt("pageSize", 30);
        if (size < 1 || size > 50) throw new IllegalArgumentException();
        return size;
    }
    static int pageNumber(Bundle b) {
        int page = b.getInt("page", 0);
        if (page < 0 || page > 10000) throw new IllegalArgumentException();
        return page;
    }
    static void page(List<?> items, Bundle in, Bundle out, int offset) throws Exception {
        int size = pageSize(in), page = pageNumber(in), start = Math.min(items.size(), page * size);
        ArrayList<Bundle> result = new ArrayList<Bundle>();
        for (int i = start; i < Math.min(items.size(), start + size); i++) result.add(song(items.get(i), offset + i));
        out.putParcelableArrayList("items", result);
        out.putInt("total", items.size());
        out.putInt("page", page);
        out.putBoolean("hasMore", start + size < items.size());
    }
    static void lists(Bundle in, Bundle out) throws Exception {
        ArrayList<Bundle> values = new ArrayList<Bundle>();
        String[][] fixed = {{"queue", "当前队列"}, {"favorites", "我喜欢听"}, {"history","最近播放"}};
        for (String[] pair : fixed) {
            Bundle b = new Bundle(); b.putString("listId", pair[0]); b.putString("name", pair[1]); values.add(b);
        }
        Object mgr = staticCall("c6.b", "j");
        Object loaded = invoke(mgr, "F1");
        Set<String> names = new HashSet<String>();
        if (loaded instanceof Collection) for (Object item : (Collection<?>) loaded) {
            String type = invoke(item, "s").toString();
            if (!MusicApi.visibleType(type)) continue;
            String name = (String) invoke(item, "getName");
            if (names.add(name)) {
                Bundle b = new Bundle(); b.putString("listId", "named:" + Uri.encode(name));
                b.putString("name", trim(name, 512)); values.add(b);
            }
        }
        int size = pageSize(in), start = Math.min(values.size(), pageNumber(in) * size);
        out.putParcelableArrayList("lists", new ArrayList<Bundle>(values.subList(start, Math.min(values.size(), start + size))));
        out.putInt("total", values.size()); out.putBoolean("hasMore", start + size < values.size());
    }

    static void lyrics(Bundle in, Bundle out) throws Exception {
        String current = track();
        out.putString("trackKey", current);
        out.putLong("revision", lyricRevision);
        JSONArray lines = new JSONArray();
        int code = -1;
        if (!current.isEmpty() && current.equals(lyricTrack) && !lyricJson.isEmpty()) {
            JSONObject root = new JSONObject(lyricJson);
            code = root.optInt("resultCode", -1);
            JSONArray data = root.optJSONArray("AUDIO_LYRIC");
            if (data != null) lines = data;
        }
        out.putInt("resultCode", code);
        int size = pageSize(in), start = Math.min(lines.length(), pageNumber(in) * size);
        ArrayList<Bundle> result = new ArrayList<Bundle>();
        for (int i = start; i < Math.min(lines.length(), start + size); i++) {
            JSONObject row = lines.getJSONObject(i);
            Bundle b = new Bundle(); b.putLong("startMs", row.optLong("startTime"));
            b.putLong("durationMs", Math.max(0, row.optLong("time")));
            b.putString("text", trim(row.optString("text"), 1024)); result.add(b);
        }
        out.putParcelableArrayList("lines", result);
        out.putInt("total", lines.length()); out.putBoolean("hasMore", start + size < lines.length());
    }
    static void currentLyric(Bundle out) throws Exception {
        String key=track();out.putString("trackKey",key);out.putLong("revision",lyricRevision);
        long position=((Number)invoke(manager(),"t0")).longValue();out.putLong("positionMs",position);out.putInt("index",-1);out.putInt("resultCode",-1);
        if(key.isEmpty() || !key.equals(lyricTrack) || lyricJson.isEmpty())return;
        JSONObject root=new JSONObject(lyricJson);out.putInt("resultCode",root.optInt("resultCode",-1));
        JSONArray rows=root.optJSONArray("AUDIO_LYRIC");if(rows==null)return;
        int selected=-1;for(int i=0;i<rows.length();i++){if(rows.getJSONObject(i).optLong("startTime")>position)break;selected=i;}
        out.putInt("index",selected);
        for(int delta=-1;delta<=1;delta++){
            int index=selected+delta;if(index<0 || index>=rows.length())continue;
            JSONObject row=rows.getJSONObject(index);Bundle b=new Bundle();b.putLong("startMs",row.optLong("startTime"));b.putLong("durationMs",Math.max(0,row.optLong("time")));b.putString("text",trim(row.optString("text"),1024));
            out.putBundle(delta<0?"previous":delta==0?"current":"next",b);
        }
    }

    final class Search implements Runnable {
        final int uid; final String id; final ResultReceiver cb;
        Object task; boolean done;
        Search(int uid, String id, ResultReceiver cb) { this.uid = uid; this.id = id; this.cb = cb; }
        @Override public void run() { finish(reply("TIMEOUT", id), true); }
        void finish(Bundle result, boolean cancel) {
            if (done) return; done = true;
            searches.remove(uid + ":" + id); MAIN.removeCallbacks(this);
            if (cancel && task != null) try { invoke(task, "cancel"); } catch (Exception ignored) {}
            send(cb, result);
        }
    }
    Search begin(int uid,String id,ResultReceiver cb) {
        String key=uid+":"+id;
        if(searches.containsKey(key) || saved.containsKey(key)){send(cb,reply("DUPLICATE_REQUEST",id));return null;}
        int count=0;for(Search s:searches.values())if(s.uid==uid)count++;
        if(count>=2 || searches.size()>=8){send(cb,reply("BUSY",id));return null;}
        Search pending=new Search(uid,id,cb);searches.put(key,pending);MAIN.postDelayed(pending,20000);return pending;
    }
    void save(int uid,String id,List<?> items) {
        String prefix=uid+":";int count=0;for(String key:saved.keySet())if(key.startsWith(prefix))count++;
        java.util.Iterator<String> old=saved.keySet().iterator();
        while(old.hasNext() && count>=2)if(old.next().startsWith(prefix)){old.remove();count--;}
        if(saved.size()>=16)saved.remove(saved.keySet().iterator().next());
        saved.put(prefix+id,new Saved(new ArrayList<Object>(items)));
    }
    List<?> result(int uid,String id) {
        Saved s=saved.get(uid+":"+id);return s==null || s.expires<SystemClock.elapsedRealtime()?null:new ArrayList<Object>(s.items);
    }
    boolean cancelRequest(int uid,String id) {
        Search s=searches.get(uid+":"+id);if(s==null)return false;s.finish(reply("CANCELLED",id),true);return true;
    }
    private static final class Saved {
        final List<?> items; final long expires = SystemClock.elapsedRealtime() + 120000;
        Saved(List<?> items) { this.items = items; }
    }
    private void cleanup() {
        long now = SystemClock.elapsedRealtime();
        java.util.Iterator<Map.Entry<String, Saved>> it = saved.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().expires < now) it.remove();
    }
    private void search(Bundle in, final int uid, final String id, ResultReceiver cb) throws Exception {
        final String key = uid + ":" + id;
        String query = in.getString("query", "").trim();
        final int page = pageNumber(in), size = pageSize(in);
        if (query.isEmpty() || query.length() > 200) throw new IllegalArgumentException();
        final Search pending = begin(uid,id,cb);
        if(pending==null)return;
        try {
            Class<?> type = Class.forName("w8.d");
            Object callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, final Object[] args) {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "KwBridgeSearchCallback";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                    }
                    if ("d".equals(method.getName()) && args != null && args.length == 1) MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (pending.done) return;
                            try {
                                Object response = args[0];
                                if (!(Boolean) BridgeProvider.invoke(response, "o")) {
                                    Bundle err = reply("SEARCH_FAILED", id);
                                    err.putInt("upstreamCode", ((Number) BridgeProvider.invoke(response, "b")).intValue());
                                    pending.finish(err, false); return;
                                }
                                Object data = BridgeProvider.invoke(response, "c");
                                List<?> items = data == null ? new ArrayList<Object>() : (List<?>) BridgeProvider.invoke(data, "c");
                                if (items == null) items = new ArrayList<Object>();
                                items = new ArrayList<Object>(items.subList(0, Math.min(items.size(), size)));
                                // Limit retained native business objects, including completed requests.
                                save(uid,id,items);
                                Bundle result = reply("OK", id); Bundle onePage = new Bundle(); onePage.putInt("pageSize", size);
                                BridgeProvider.page(items, onePage, result, 0);
                                result.putInt("resultCount", items.size());
                                result.remove("total");
                                result.putInt("page", page);
                                result.putBoolean("hasMore", items.size() == size);
                                result.putBoolean("hasMoreEstimated", true);
                                pending.finish(result, false);
                            } catch (Exception e) { pending.finish(reply("SEARCH_FAILED", id), false); }
                        }
                    });
                    return null;
                }
            });
            // Host search pages start at zero, matching the public API.
            pending.task = Class.forName("w8.c").getMethod("q0", String.class, String.class,
                String.class, int.class, int.class, type).invoke(null, query, "", "", page, size, callback);
        } catch (Exception e) { pending.finish(reply("NOT_READY", id), true); }
    }

    public static void onLyrics(final Bundle b) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Bundle copy = b == null ? null : new Bundle(b);
            MAIN.post(new Runnable() { @Override public void run() { onLyrics(copy); }}); return;
        }
        try {
            String value = b == null ? null : b.getString("AUDIO_LYRIC");
            if (value == null) return;
            lyricJson = value.length() <= 524288 ? value : "";
            lyricTrack = track(); lyricRevision++;
            notifyUri(LYRICS);
        } catch (Throwable e) { Log.w("KwBridge", "lyrics unavailable", e); }
    }
    public static void onStateChanged() {
        synchronized (BridgeProvider.class) {
            if (stateNotificationPending) return;
            stateNotificationPending = true;
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (BridgeProvider.class) { stateNotificationPending = false; }
                stateRevision++;
                notifyUri(STATE);
            }
        }, 250);
    }
    public static void onMetadata(final Object metadata) {
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(new Runnable(){public void run(){onMetadata(metadata);}});return;}
        Artwork.metadata(metadata);onStateChanged();
    }
    public static void onListsChanged(){
        synchronized(BridgeProvider.class){if(listsNotificationPending)return;listsNotificationPending=true;}
        MAIN.postDelayed(new Runnable(){public void run(){
            synchronized(BridgeProvider.class){listsNotificationPending=false;}
            notifyUri(MusicApi.LISTS);onStateChanged();
        }},250);
    }
    public static List<Object> legacyMediaItems() {
        ArrayList<Object> result = new ArrayList<Object>();
        try {
            Object queue = invoke(player(), "V");
            if (queue == null) return result;
            List<?> items = (List<?>) invoke(queue, "z");
            Class<?> builder = Class.forName("android.support.v4.media.MediaMetadataCompat$b");
            Class<?> description = Class.forName("android.support.v4.media.MediaDescriptionCompat");
            Class<?> itemClass = Class.forName("android.support.v4.media.MediaBrowserCompat$MediaItem");
            java.lang.reflect.Constructor<?> buildMetadata = builder.getConstructor();
            java.lang.reflect.Constructor<?> buildItem = itemClass.getConstructor(description, int.class);
            String[][] fields = {{"MEDIA_ID", "f"}, {"TITLE", "g"}, {"ARTIST", "h"}, {"ALBUM", "j"}};
            for (Object music : items) {
                if (music == null) continue;
                Object b = buildMetadata.newInstance();
                for (String[] pair : fields) invoke(b, "e", new Class<?>[] {String.class, String.class}, "android.media.metadata." + pair[0], field(music, pair[1]));
                invoke(b, "c", new Class<?>[] {String.class, long.class}, "android.media.metadata.DURATION", ((Number) ReflectAccess.field(music.getClass(), "k").get(music)).longValue() * 1000L);
                Object metadata = invoke(b, "a");
                result.add(buildItem.newInstance(invoke(metadata, "d"), 2));
            }
        } catch (Exception e) { Log.w("KwBridge", "browser list unavailable", e); }
        return result;
    }
    static void notifyUri(Uri uri) {
        Context c = context;
        if (c != null) try { c.getContentResolver().notifyChange(uri, null, false); }
        catch (RuntimeException e) { Log.w("KwBridge", "observer unavailable", e); }
    }
    static Bundle reply(String status, String id) {
        Bundle b = new Bundle(); b.putString("status", status); b.putString("requestId", id); return b;
    }
    static void send(ResultReceiver receiver, Bundle value) {
        try { receiver.send("OK".equals(value.getString("status")) ? 0 : 1, value); }
        catch (RuntimeException e) { Log.w("KwBridge", "callback unavailable", e); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { throw new UnsupportedOperationException("call API only"); }
    @Override public String getType(Uri u) { return "/artwork".equals(u.getPath()) || u.getPath()!=null && u.getPath().startsWith("/artwork/")?"image/png":null; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!authorized(Binder.getCallingUid()) || !SilentStartup.consent())throw new FileNotFoundException("Artwork access denied");
        return Artwork.open(uri,mode);
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
