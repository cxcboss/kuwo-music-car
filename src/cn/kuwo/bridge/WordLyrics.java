package cn.kuwo.bridge;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Copies the host's native LRCX timing; ordinary LRC is never guessed into words. */
public final class WordLyrics {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static Snapshot latest;
    private static long revision;
    private WordLyrics() {}

    static final class Word {
        final int lineIndex, wordIndexInLine, charStart, charEnd;
        final long lineStartMs, startMs, endMs;
        final String lineText, text;
        Word(int line,int word,int from,int to,long lineStart,long start,long end,String lineText) {
            lineIndex=line;wordIndexInLine=word;charStart=from;charEnd=to;
            lineStartMs=lineStart;startMs=start;endMs=end;this.lineText=lineText;text=lineText.substring(from,to);
        }
        Bundle bundle(int index) {
            Bundle b=new Bundle();b.putInt("index",index);b.putInt("lineIndex",lineIndex);b.putInt("wordIndexInLine",wordIndexInLine);
            b.putInt("charStart",charStart);b.putInt("charEnd",charEnd);b.putString("text",text);b.putString("lineText",lineText);
            b.putLong("lineStartMs",lineStartMs);b.putLong("startMs",startMs);b.putLong("endMs",endMs);b.putLong("durationMs",endMs-startMs);
            return b;
        }
    }

    static final class Snapshot {
        final String trackKey, type;
        final int resultCode;
        final long offsetMs;
        final boolean truncated;
        final List<Word> words=new ArrayList<Word>();
        @SuppressWarnings("unchecked")
        Snapshot(String key,String status,Object lyric) throws Exception {
            trackKey=key;
            type=lyric==null?"":MusicApi.call(lyric,"getType").toString();
            resultCode="SUCCESS".equals(status)?20000:"BEGIN".equals(status)?20001:"NONE".equals(status)?20002:20003;
            if(resultCode!=20000 || !"LRCX".equals(type)) { offsetMs=0;truncated=false;return; }
            offsetMs=((Number)MusicApi.call(lyric,"n")).longValue();
            List<String> lines=(List<String>)MusicApi.call(lyric,"c");
            List<Integer> starts=(List<Integer>)MusicApi.call(lyric,"b");
            List<List<?>> timings=(List<List<?>>)MusicApi.call(lyric,"o");
            if(lines.size()!=starts.size() || lines.size()!=timings.size()) throw new IllegalArgumentException("Unaligned native lyrics");
            Class<?> span=Class.forName("cn.kuwo.mod.lyric.LyricsDefine$c");
            Field from=ReflectAccess.declaredField(span,"a"),to=ReflectAccess.declaredField(span,"b");
            Field begin=ReflectAccess.declaredField(span,"d"),finish=ReflectAccess.declaredField(span,"e");
            boolean cut=false;
            for(int line=0;line<lines.size();line++) {
                String raw=lines.get(line),text=raw.substring(0,Math.min(raw.length(),1024));
                long lineStart=starts.get(line).longValue()+offsetMs;
                List<?> spans=timings.get(line);
                for(int index=0;index<spans.size();index++) {
                    Object value=spans.get(index);
                    int startChar=((Number)from.get(value)).intValue(),endChar=((Number)to.get(value)).intValue();
                    long startMs=lineStart+((Number)begin.get(value)).longValue(),endMs=lineStart+((Number)finish.get(value)).longValue();
                    if(startChar<0 || endChar<startChar || endChar>raw.length() || endMs<startMs) throw new IllegalArgumentException("Invalid native word span");
                    if(endChar>text.length()) { cut=true;continue; }
                    words.add(new Word(line,index,startChar,endChar,lineStart,startMs,endMs,text));
                }
                cut|=raw.length()>text.length();
            }
            truncated=cut;
        }
    }

    public static void capture(final Object music,final Object status,final Object lyric,final boolean keepPrevious) {
        // The host keeps its previous lyrics while this kind of refresh is pending or fails.
        if(keepPrevious && !"SUCCESS".equals(String.valueOf(status)))return;
        if(Looper.myLooper()!=Looper.getMainLooper()) { MAIN.post(() -> capture(music,status,lyric,keepPrevious));return; }
        try {
            String current=BridgeProvider.track();
            String key=music==null?current:BridgeProvider.song(music,-1).getString("trackKey","");
            if(!key.equals(current))return;
            latest=new Snapshot(key,String.valueOf(status),lyric);
        } catch(Exception e) { latest=null;Log.w("KwBridge","Native word lyrics unavailable",e); }
        revision++;
        BridgeProvider.notifyUri(BridgeProvider.LYRICS);
    }

    static void read(String method,Bundle in,Bundle out) throws Exception {
        String key=BridgeProvider.track();
        String expected=in.getString("trackKey","");
        if(!expected.isEmpty() && !expected.equals(key)) { out.putString("status","STALE_TRACK");return; }
        if(in.containsKey("revision") && in.getLong("revision")!=revision) { out.putString("status","STALE_LYRICS");return; }
        Snapshot data=latest;
        boolean matching=data!=null && data.trackKey.equals(key);
        List<Word> words=matching?data.words:Collections.emptyList();
        boolean available=matching && data.resultCode==20000 && !words.isEmpty();
        out.putString("trackKey",key);out.putLong("revision",revision);out.putString("lyricType",matching?data.type:"");
        out.putInt("resultCode",matching?data.resultCode:-1);out.putLong("offsetMs",matching?data.offsetMs:0);
        out.putBoolean("wordTimingAvailable",available);out.putBoolean("truncated",matching && data.truncated);
        out.putString("source",available?"native_lrcx":"");
        out.putString("reason",available?"":key.isEmpty()?"NO_TRACK":!matching?"LYRICS_PENDING":data.resultCode==20001?"LYRICS_PENDING":data.resultCode==20003?"LYRICS_FAILED":"NO_WORD_TIMING");
        out.putInt("total",words.size());
        if("getWordLyrics".equals(method)) {
            int page=BridgeProvider.pageNumber(in),size=BridgeProvider.pageSize(in);
            int start=Math.min(words.size(),page*size),end=Math.min(words.size(),start+size);
            ArrayList<Bundle> items=new ArrayList<Bundle>();
            for(int index=start;index<end;index++)items.add(words.get(index).bundle(index));
            out.putInt("page",page);out.putInt("pageSize",size);out.putBoolean("hasMore",end<words.size());out.putParcelableArrayList("items",items);
        } else {
            long position=((Number)MusicApi.call(BridgeProvider.manager(),"t0")).longValue();
            out.putLong("positionMs",position);out.putLong("sampledAtElapsedMs",SystemClock.elapsedRealtime());
            int selected=-1;
            for(int index=0;index<words.size();index++) { if(words.get(index).startMs>position)break;selected=index; }
            out.putInt("index",selected);out.putBoolean("active",false);out.putFloat("progress",0);
            if(selected>=0) {
                Word word=words.get(selected);out.putBundle("word",word.bundle(selected));
                out.putBoolean("active",position<word.endMs);
                out.putFloat("progress",word.endMs==word.startMs?1:Math.min(1,(float)(position-word.startMs)/(word.endMs-word.startMs)));
            }
            if(selected+1<words.size())out.putBundle("next",words.get(selected+1).bundle(selected+1));
        }
    }
}
