package cn.kuwo.bridge;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fixed adapters to the original authenticated catalog client. */
final class NetworkCatalog {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final String[] METHODS={"getSongById","getDailyRecommendations","getRecommendedTracks","getHotKeywords","getSearchSuggestions",
        "searchAlbums","searchArtists","searchPlaylists","getRecommendedPlaylists","getOnlinePlaylistItems","getAlbumTracks","getArtistTracks",
        "getCharts","getChartTracks","getAlbumInfo","getArtistInfo","getOnlinePlaylistInfo"};
    private static final int MUSIC_LIST=0,MUSIC_KW=1,MUSIC_ONE=2,CATALOG_KW=3,CATALOG_ONE=4,STRINGS=5,CHARTS=6;
    static boolean handles(String method){return Arrays.asList(METHODS).contains(method);}
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object enumValue(String name,String value)throws Exception{return Enum.valueOf((Class)Class.forName("cn.kuwo.open.base."+name),value);}
    private static Object bean(String name,long id)throws Exception{
        Object b=Class.forName("cn.kuwo.base.bean.quku."+name).getConstructor().newInstance();MusicApi.call(b,"j",new Class<?>[]{String.class},Long.toString(id));return b;
    }
    private static long id(Bundle b){long id;try{id=Long.parseLong(b.getString("id",""));}catch(RuntimeException e){throw new IllegalArgumentException("positive id string required");}if(id<=0)throw new IllegalArgumentException();return id;}
    private static String query(Bundle in){String q=in.getString("query","").trim();if(q.isEmpty()||q.length()>200)throw new IllegalArgumentException();return q;}
    private static String sort(Bundle in){String s=in.getString("sort","hot");if(!"hot".equals(s)&&!"new".equals(s))throw new IllegalArgumentException();return "new".equals(s)?"New":"Hot";}
    static void request(final BridgeProvider host,String method,final Bundle in,final int uid,final String id,ResultReceiver cb)throws Exception{
        final int size=BridgeProvider.pageSize(in),page=BridgeProvider.pageNumber(in);
        Class<?> callback=Class.forName("w8.d");String target;Class<?>[] params;Object[] args;int kind;
        if("getSongById".equals(method)){target="K";params=new Class<?>[]{long.class};args=new Object[]{id(in)};kind=MUSIC_ONE;}
        else if("getDailyRecommendations".equals(method)){target="x";params=new Class<?>[]{int.class};args=new Object[]{page};kind=MUSIC_KW;}
        else if("getRecommendedTracks".equals(method)){target="F";params=new Class<?>[]{int.class,int.class};args=new Object[]{page,size};kind=MUSIC_LIST;}
        else if("getHotKeywords".equals(method)){target="D";params=new Class<?>[]{int.class};args=new Object[]{size};kind=STRINGS;}
        else if("getSearchSuggestions".equals(method)){target="p0";params=new Class<?>[]{String.class};args=new Object[]{query(in)};kind=STRINGS;}
        else if("searchAlbums".equals(method)||"searchArtists".equals(method)||"searchPlaylists".equals(method)){
            target="searchAlbums".equals(method)?"n0":"searchArtists".equals(method)?"o0":"V";
            params=new Class<?>[]{String.class,int.class,int.class};args=new Object[]{query(in),page,size};kind=CATALOG_KW;
        }else if("getRecommendedPlaylists".equals(method)){
            Class<?> type=Class.forName("cn.kuwo.open.base.FetchSongListType");target="S";params=new Class<?>[]{type,int.class,int.class};args=new Object[]{enumValue("FetchSongListType",sort(in)),page,size};kind=CATALOG_KW;
        }else if("getOnlinePlaylistItems".equals(method)){
            Class<?> type=Class.forName("cn.kuwo.open.base.FetchSongLitMusicType");target="L";params=new Class<?>[]{long.class,int.class,int.class,type};args=new Object[]{id(in),page,size,enumValue("FetchSongLitMusicType","Filter")};kind=MUSIC_KW;
        }else if("getAlbumTracks".equals(method)){
            Object album=bean("AlbumInfo",id(in));target="h";params=new Class<?>[]{album.getClass(),int.class,int.class};args=new Object[]{album,page,size};kind=MUSIC_KW;
        }else if("getArtistTracks".equals(method)){
            Object artist=bean("ArtistInfo",id(in));Class<?> type=Class.forName("cn.kuwo.open.base.SortArtistAlbumType");target="n";
            params=new Class<?>[]{artist.getClass(),type,int.class,int.class};args=new Object[]{artist,enumValue("SortArtistAlbumType",sort(in)),page,size};kind=MUSIC_KW;
        }else if("getCharts".equals(method)){target="j";params=new Class<?>[]{int.class,int.class};args=new Object[]{page,size};kind=CHARTS;}
        else if("getChartTracks".equals(method)){target="q";params=new Class<?>[]{long.class,int.class,int.class};args=new Object[]{id(in),page,size};kind=MUSIC_KW;}
        else if("getAlbumInfo".equals(method)||"getArtistInfo".equals(method)||"getOnlinePlaylistInfo".equals(method)){
            target="getAlbumInfo".equals(method)?"J":"getArtistInfo".equals(method)?"m":"X";params=new Class<?>[]{long.class};args=new Object[]{id(in)};kind=CATALOG_ONE;
        }else throw new IllegalArgumentException();
        final int resultKind=kind;
        final BridgeProvider.Search pending=host.begin(uid,id,cb);if(pending==null)return;
        Object receiver=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},new InvocationHandler(){
            public Object invoke(Object proxy,Method m,final Object[] values){
                if(m.getDeclaringClass()==Object.class){if("hashCode".equals(m.getName()))return System.identityHashCode(proxy);if("equals".equals(m.getName()))return proxy==values[0];return "KuwoCatalogCallback";}
                if("d".equals(m.getName())&&values!=null&&values.length==1)MAIN.post(new Runnable(){public void run(){
                    if(pending.done)return;
                    try{
                        Object response=values[0];
                        if(response==null || !(Boolean)MusicApi.call(response,"o")){
                            Bundle failed=BridgeProvider.reply("UPSTREAM_ERROR",id);if(response!=null)failed.putInt("upstreamCode",((Number)MusicApi.call(response,"b")).intValue());pending.finish(failed,false);return;
                        }
                        Object data=MusicApi.call(response,"c");Bundle out=BridgeProvider.reply("OK",id);
                        List<?> rows;
                        if(resultKind==MUSIC_ONE||resultKind==CATALOG_ONE)rows=data==null?Collections.emptyList():Collections.singletonList(data);
                        else if(resultKind==MUSIC_KW||resultKind==CATALOG_KW)rows=data==null?Collections.emptyList():(List<?>)MusicApi.call(data,"c");
                        else rows=data==null?Collections.emptyList():(List<?>)data;
                        if(rows==null)rows=Collections.emptyList();
                        int rawCount=rows.size();
                        // Daily discovery has a host-defined page size; return up to the transport cap, not the caller size.
                        int limit="getDailyRecommendations".equals(method)?50:size;
                        List<?> bounded=new ArrayList<Object>(rows.subList(0,Math.min(rows.size(),limit)));
                        out.putInt("page",page);out.putInt("resultCount",bounded.size());out.putInt("upstreamPageCount",rawCount);
                        boolean more="getDailyRecommendations".equals(method)?rawCount>0:rawCount>=limit;
                        out.putBoolean("truncated",rawCount>bounded.size());out.putBoolean("hasMore",more);out.putBoolean("hasMoreEstimated",true);
                        if(resultKind==MUSIC_LIST||resultKind==MUSIC_KW||resultKind==MUSIC_ONE){
                            host.save(uid,id,bounded);Bundle first=new Bundle();first.putInt("pageSize",50);BridgeProvider.page(bounded,first,out,0);out.remove("total");out.putInt("page",page);
                            out.putBoolean("hasMore",more);out.putString("resultRequestId",id);
                            if(resultKind==MUSIC_ONE){if(bounded.isEmpty())out.putString("status","NOT_FOUND");else out.putBundle("song",BridgeProvider.song(bounded.get(0),0));}
                        }else if(resultKind==STRINGS){
                            ArrayList<String> text=new ArrayList<String>();for(Object row:bounded){String s=String.valueOf(row);text.add(s.substring(0,Math.min(512,s.length())));}out.putStringArrayList("values",text);out.putBoolean("hasMore",false);
                        }else{
                            ArrayList<Bundle> items=new ArrayList<Bundle>();
                            int chartCount=0;
                            for(Object row:bounded){if(row==null)continue;if(resultKind==CHARTS){List<?> charts=(List<?>)MusicApi.call(row,"G");if(charts!=null){chartCount+=charts.size();for(Object chart:charts){if(items.size()>=50)break;items.add(catalog(chart));}}}else items.add(catalog(row));}
                            out.putParcelableArrayList("items",items);if(resultKind==CATALOG_ONE){out.putBoolean("hasMore",false);if(items.isEmpty())out.putString("status","NOT_FOUND");else out.putBundle("item",items.get(0));}
                            if(resultKind==CHARTS){out.putInt("resultCount",items.size());out.putBoolean("truncated",chartCount>items.size() || rawCount>bounded.size());}
                        }
                        pending.finish(out,false);
                    }catch(Exception e){pending.finish(BridgeProvider.reply("UPSTREAM_ERROR",id),false);}
                }});
                return null;
            }
        });
        Class<?>[] signature=Arrays.copyOf(params,params.length+1);signature[params.length]=callback;
        Object[] arguments=Arrays.copyOf(args,args.length+1);arguments[args.length]=receiver;
        try{pending.task=ReflectAccess.method(Class.forName("w8.c"),target,signature).invoke(null,arguments);}
        catch(Exception e){pending.finish(BridgeProvider.reply("NOT_READY",id),true);}
    }
    private static Bundle catalog(Object row)throws Exception{
        Bundle b=new Bundle();Object title=MusicApi.call(row,"getName");String name=title==null?"":title.toString();b.putString("id",String.valueOf(MusicApi.call(row,"b")));b.putString("name",name.substring(0,Math.min(512,name.length())));
        b.putString("coverUrl",MusicApi.http((String)MusicApi.call(row,"f")));return b;
    }
}
