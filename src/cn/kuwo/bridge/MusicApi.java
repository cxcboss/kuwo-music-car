package cn.kuwo.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit retained music operations, never a general reflection or preference endpoint. */
final class MusicApi {
    static final Uri LISTS=Uri.parse("content://"+BridgeProvider.AUTHORITY+"/lists");
    static final String[] METHODS={"getAppInfo","initialize","getStartupConfig","setStartupConfig","getStartupStatus",
        "getSongInfo","getPlaybackInfo","getArtwork","getCurrentLyric","getQueue","getHistory","getFavoriteState","setFavorite","toggleFavorite",
        "playPause","playResultItem","playQueue","addToQueue","playNext","removeQueueItem","clearQueue",
        "getPlaylists","getPlaylistInfo","createPlaylist","renamePlaylist","deletePlaylist","addPlaylistItem","removePlaylistItem","clearPlaylist",
        "getVolume","setVolume","adjustVolume","getAccountState","cancelRequest",
        "getSongById","getDailyRecommendations","getRecommendedTracks","getHotKeywords","getSearchSuggestions",
        "searchAlbums","searchArtists","searchPlaylists","getRecommendedPlaylists","getOnlinePlaylistItems","getAlbumTracks","getArtistTracks",
        "getCharts","getChartTracks","getAlbumInfo","getArtistInfo","getOnlinePlaylistInfo"};
    static boolean handles(String method){return Arrays.asList(METHODS).contains(method);}
    static boolean early(String m){return "getAppInfo".equals(m)||"initialize".equals(m)||"getStartupConfig".equals(m)||"getStartupStatus".equals(m)||"setStartupConfig".equals(m)||"cancelRequest".equals(m);}
    static String http(String value){if(value==null || value.length()>4096)return "";Uri u=Uri.parse(value);return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null?value:"";}
    static boolean visibleType(String type){return Arrays.asList("LIST_MY_FAVORITE","LIST_DEFAULT","LIST_PC_DEFAULT","LIST_RECENTLY_PLAY","LIST_USER_CREATE","LIST_TEMPORARY").contains(type);}
    static Object call(Object o,String method,Class<?>[] types,Object...args)throws Exception{return BridgeProvider.invoke(o,method,types,args);}
    static Object call(Object o,String method)throws Exception{return BridgeProvider.invoke(o,method);}
    static Object stat(String cls,String method,Class<?>[] types,Object...args)throws Exception{return ReflectAccess.method(Class.forName(cls),method,types).invoke(null,args);}
    static Class<?> music()throws Exception{return Class.forName("cn.kuwo.base.bean.Music");}
    static Object listMgr()throws Exception{return BridgeProvider.staticCall("c6.b","j");}
    static boolean loggedIn()throws Exception{return (Boolean)BridgeProvider.staticCall("s8.d","k");}
    private static final class Failure extends Exception{final String status;Failure(String s){status=s;}}
    private static void require(boolean condition,String status)throws Failure{if(!condition)throw new Failure(status);}
    private static boolean favorite(Object song)throws Exception{return song!=null && (Boolean)stat("q5.e1","r",new Class<?>[]{music()},song);}
    private static String name(String id)throws Exception{
        if("favorites".equals(id))return "我喜欢听";
        if("history".equals(id))return "最近播放";
        if("queue".equals(id)){Object q=call(BridgeProvider.player(),"V");require(q!=null,"NOT_READY");return String.valueOf(call(q,"getName"));}
        if(id.startsWith("named:")&&id.length()<=1024)return Uri.decode(id.substring(6));
        throw new IllegalArgumentException();
    }
    private static Object playlist(String id)throws Exception{
        Object list=call(listMgr(),"A4",new Class<?>[]{String.class},name(id));
        require(list!=null,"NOT_FOUND");require(visibleType(String.valueOf(call(list,"s"))),"UNSUPPORTED_CONTENT");return list;
    }
    private static String writable(String id,boolean customOnly)throws Exception{
        Object list=playlist(id);String type=String.valueOf(call(list,"s"));
        require(customOnly?"LIST_USER_CREATE".equals(type):"LIST_USER_CREATE".equals(type)||"LIST_TEMPORARY".equals(type),"READ_ONLY_LIST");
        return String.valueOf(call(list,"getName"));
    }
    private static List<?> checkedList(Bundle in,String id)throws Exception{
        List<?> list=BridgeProvider.list(id);
        require(BridgeProvider.revision(list).equals(in.getString("revision","")),"STALE_LIST");return list;
    }
    static Object resolve(BridgeProvider host,Bundle ref,int uid)throws Exception{
        String result=ref.getString("resultRequestId",ref.getString("searchRequestId",""));
        if(!result.isEmpty()){
            List<?> list=host.result(uid,result);require(list!=null,"STALE_SEARCH");
            int i=ref.getInt("index",-1);require(i>=0 && i<list.size(),"INVALID_ARGUMENT");return list.get(i);
        }
        String id=ref.getString("listId","");
        if(!id.isEmpty()){
            List<?> list=checkedList(ref,id);int i=ref.getInt("index",-1);require(i>=0 && i<list.size(),"INVALID_ARGUMENT");return list.get(i);
        }
        Object current=call(BridgeProvider.manager(),"v0");require(current!=null,"NO_TRACK");
        String key=ref.getString("trackKey","");require(key.isEmpty() || key.equals(BridgeProvider.track()),"STALE_TRACK");return current;
    }
    private static Bundle reference(Bundle in){Bundle b=in.getBundle("songRef");return b==null?in:b;}
    private static void changed(Bundle out,boolean accepted){out.putBoolean("accepted",accepted);out.putBoolean("stateConfirmed",false);if(!accepted)out.putString("status","OPERATION_FAILED");BridgeProvider.onListsChanged();}
    private static void setFavorite(Object song,boolean target,Bundle out)throws Exception{
        require(loggedIn(),"LOGIN_REQUIRED");require((Boolean)stat("q5.e1","B",new Class<?>[]{music()},song),"UNSUPPORTED_CONTENT");
        require(!"1".equals(BridgeProvider.field(song,"s")),"UNSUPPORTED_CONTENT");
        boolean before=favorite(song);int code=0;
        if(before!=target)code=((Number)stat("q5.e1","n",new Class<?>[]{music(),int.class},song,0)).intValue();
        boolean after=favorite(song);out.putBoolean("favorite",after);out.putInt("upstreamCode",code);
        changed(out,code==0);out.putBoolean("stateConfirmed",after==target);
    }
    private static Bundle info(String id)throws Exception{
        Object list=playlist(id);List<?> items=BridgeProvider.list(id);Bundle b=new Bundle();
        b.putString("listId",id);b.putString("name",String.valueOf(call(list,"getName")));
        String type=String.valueOf(call(list,"s"));b.putString("type",type);b.putInt("total",items.size());
        b.putString("revision",BridgeProvider.revision(items));b.putBoolean("writable","LIST_USER_CREATE".equals(type)||"LIST_TEMPORARY".equals(type));
        b.putBoolean("canRename","LIST_USER_CREATE".equals(type));b.putBoolean("canDelete","LIST_USER_CREATE".equals(type));return b;
    }
    static void dispatch(BridgeProvider host,Context context,String method,Bundle in,int uid,String id,ResultReceiver cb)throws Exception{
        Bundle out=BridgeProvider.reply("OK",id);
        try{
            if("getAppInfo".equals(method)){
                PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
                out.putString("packageName",context.getPackageName());out.putString("versionName",p.versionName);out.putLong("versionCode",p.versionCode);out.putInt("apiVersion",2);
            }else if("getStartupConfig".equals(method)||"getStartupStatus".equals(method))out.putAll(SilentStartup.status());
            else if("setStartupConfig".equals(method)){
                require(in.containsKey("autoStartEnabled"),"INVALID_ARGUMENT");SilentStartup.setEnabled(in.getBoolean("autoStartEnabled"));out.putAll(SilentStartup.status());out.putBoolean("accepted",true);out.putBoolean("stateConfirmed",true);
            }else if("initialize".equals(method)){out.putAll(SilentStartup.start("bridge"));out.putString("requestId",id);out.putAll(SilentStartup.status());}
            else if("cancelRequest".equals(method))out.putBoolean("cancelled",host.cancelRequest(uid,in.getString("targetRequestId","")));
            else if(NetworkCatalog.handles(method)){NetworkCatalog.request(host,method,in,uid,id,cb);return;}
            else if("getSongInfo".equals(method))out.putBundle("song",BridgeProvider.song(resolve(host,reference(in),uid),-1));
            else if("getPlaybackInfo".equals(method)){
                Bundle state=BridgeProvider.state();out.putBundle("state",state);Bundle caps=new Bundle();
                caps.putBoolean("canSeek",state.getInt("durationMs")>0);caps.putBoolean("canSetMode",state.getInt("contentType")!=4 && state.getInt("contentType")!=5);
                caps.putStringArray("modes",new String[]{"single","sequential","repeat_all","shuffle"});out.putBundle("controls",caps);
            }else if("getArtwork".equals(method)){
                // Ask the existing media-session builder for its cached bitmap only; false disables image download.
                Object service=BridgeProvider.staticCall("cn.kuwo.mod.mediaSession.KwMediaSessionService","j");
                if(service!=null && Artwork.uri(BridgeProvider.track()).isEmpty())Artwork.metadata(call(service,"m",new Class<?>[]{boolean.class,boolean.class},true,false));
                out.putAll(Artwork.info());
            }else if("getQueue".equals(method)||"getHistory".equals(method)){
                String listId="getQueue".equals(method)?"queue":"history";List<?> list=BridgeProvider.list(listId);
                BridgeProvider.page(list,in,out,0);out.putString("listId",listId);out.putString("revision",BridgeProvider.revision(list));
                if("queue".equals(listId))out.putInt("activeIndex",((Number)call(BridgeProvider.player(),"G4")).intValue());
            }else if("getPlaylists".equals(method))BridgeProvider.lists(in,out);
            else if("getPlaylistInfo".equals(method))out.putBundle("list",info(in.getString("listId","queue")));
            else if("getFavoriteState".equals(method))out.putBoolean("favorite",favorite(resolve(host,reference(in),uid)));
            else if("setFavorite".equals(method)||"toggleFavorite".equals(method)){
                require(!"setFavorite".equals(method)||in.containsKey("favorite"),"INVALID_ARGUMENT");Object song=resolve(host,reference(in),uid);
                setFavorite(song,"toggleFavorite".equals(method)?!favorite(song):in.getBoolean("favorite"),out);
            }else if("playPause".equals(method)){
                PerformanceHooks.playbackCommand();Object service=BridgeProvider.staticCall("cn.kuwo.mod.mediaSession.KwMediaSessionService","j");require(service!=null,"NOT_READY");
                Object callback=Class.forName("cn.kuwo.mod.mediaSession.KwMediaSessionService$d").getConstructor(service.getClass()).newInstance(service);
                call(callback,"PLAYING".equals(BridgeProvider.state().getString("state"))?"h":"i");BridgeProvider.command(out);
            }else if("playResultItem".equals(method)){
                List<?> items=host.result(uid,in.getString("resultRequestId",in.getString("searchRequestId","")));require(items!=null,"STALE_SEARCH");
                int i=in.getInt("index",-1);require(i>=0&&i<items.size(),"INVALID_ARGUMENT");
                PerformanceHooks.playbackCommand();call(BridgeProvider.controller(),"B0",new Class<?>[]{List.class,int.class},items,i);BridgeProvider.command(out);
            }else if("playQueue".equals(method)){
                ArrayList<Bundle> refs=in.getParcelableArrayList("songRefs");require(refs!=null&&!refs.isEmpty()&&refs.size()<=50,"INVALID_ARGUMENT");
                ArrayList<Object> items=new ArrayList<Object>();for(Bundle ref:refs){require(ref!=null,"INVALID_ARGUMENT");items.add(resolve(host,ref,uid));}
                int index=in.getInt("startIndex",0);require(index>=0&&index<items.size(),"INVALID_ARGUMENT");
                PerformanceHooks.playbackCommand();call(BridgeProvider.controller(),"B0",new Class<?>[]{List.class,int.class},items,index);BridgeProvider.command(out);
            }else if("addToQueue".equals(method)||"playNext".equals(method)){
                List<?> queue=checkedList(in,"queue");String list=writable("queue",false);Object song=resolve(host,reference(in),uid);
                int index="playNext".equals(method)?Math.min(queue.size(),Math.max(0,((Number)call(BridgeProvider.player(),"G4")).intValue()+1)):queue.size();
                int result=((Number)call(listMgr(),"J0",new Class<?>[]{String.class,music(),int.class},list,song,index)).intValue();changed(out,result>=0);out.putInt("upstreamCode",result);out.putInt("index",index);
            }else if("removeQueueItem".equals(method)||"clearQueue".equals(method)){
                List<?> list=checkedList(in,"queue");String listName=writable("queue",false);boolean accepted;
                if("clearQueue".equals(method))accepted=(Boolean)call(listMgr(),"Q",new Class<?>[]{String.class},listName);
                else{int index=in.getInt("index",-1);require(index>=0&&index<list.size(),"INVALID_ARGUMENT");accepted=(Boolean)call(listMgr(),"U2",new Class<?>[]{String.class,int.class},listName,index);}
                changed(out,accepted);
            }else if("createPlaylist".equals(method)){
                require(loggedIn(),"LOGIN_REQUIRED");String value=in.getString("name","").trim();require(!value.isEmpty()&&value.length()<=100,"INVALID_ARGUMENT");
                Class<?> type=Class.forName("cn.kuwo.base.bean.ListType");Object kind=enumValue(type,"LIST_USER_CREATE");
                Object created=call(listMgr(),"M1",new Class<?>[]{type,String.class},kind,value);changed(out,created!=null);
                if(created!=null)out.putString("listId","named:"+Uri.encode(value));
            }else if("renamePlaylist".equals(method)||"deletePlaylist".equals(method)||"clearPlaylist".equals(method)||"removePlaylistItem".equals(method)||"addPlaylistItem".equals(method)){
                require(loggedIn(),"LOGIN_REQUIRED");String listId=in.getString("listId","");List<?> rows=checkedList(in,listId);String value=writable(listId,true);boolean accepted;
                if("renamePlaylist".equals(method)){String newName=in.getString("name","").trim();require(!newName.isEmpty()&&newName.length()<=100,"INVALID_ARGUMENT");accepted=(Boolean)call(listMgr(),"O",new Class<?>[]{String.class,String.class},value,newName);if(accepted)out.putString("listId","named:"+Uri.encode(newName));}
                else if("deletePlaylist".equals(method))accepted=(Boolean)call(listMgr(),"K2",new Class<?>[]{String.class},value);
                else if("clearPlaylist".equals(method))accepted=(Boolean)call(listMgr(),"Q",new Class<?>[]{String.class},value);
                else if("removePlaylistItem".equals(method)){int index=in.getInt("index",-1);require(index>=0&&index<rows.size(),"INVALID_ARGUMENT");accepted=(Boolean)call(listMgr(),"U2",new Class<?>[]{String.class,int.class},value,index);}
                else{Bundle ref=in.getBundle("songRef");require(ref!=null,"INVALID_ARGUMENT");Object song=resolve(host,ref,uid);int result=((Number)call(listMgr(),"J0",new Class<?>[]{String.class,music(),int.class},value,song,rows.size())).intValue();accepted=result>=0;out.putInt("upstreamCode",result);}
                changed(out,accepted);
            }else if("getVolume".equals(method)||"setVolume".equals(method)||"adjustVolume".equals(method)){
                AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),before=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                if(!"getVolume".equals(method)){
                    int value;
                    if("adjustVolume".equals(method)){int delta=in.getInt("delta",0);require(delta>=-10&&delta<=10,"INVALID_ARGUMENT");value=Math.max(0,Math.min(max,before+delta));}
                    else{require(in.containsKey("volume"),"INVALID_ARGUMENT");value=in.getInt("volume",-1);require(value>=0&&value<=max,"INVALID_ARGUMENT");}
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);out.putBoolean("accepted",true);out.putBoolean("stateConfirmed",audio.getStreamVolume(AudioManager.STREAM_MUSIC)==value);
                }
                out.putInt("volume",audio.getStreamVolume(AudioManager.STREAM_MUSIC));out.putInt("maxVolume",max);out.putString("scope","device_music_stream");
            }else if("getAccountState".equals(method))out.putBoolean("loggedIn",loggedIn());
            else if("getCurrentLyric".equals(method))BridgeProvider.currentLyric(out);
            else out.putString("status","UNSUPPORTED");
        }catch(Failure failure){out=BridgeProvider.reply(failure.status,id);}
        BridgeProvider.send(cb,out);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object enumValue(Class<?> type,String name){return Enum.valueOf((Class)type,name);}
}
