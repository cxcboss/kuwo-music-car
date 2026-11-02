package cn.kuwo.bridge;

import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Main-thread orchestration only; playback and network implementations remain the host's. */
public final class PerformanceHooks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Object, Binding> BINDINGS = new WeakHashMap<Object, Binding>();
    private static final WeakHashMap<Object, Suggestion> SUGGESTIONS = new WeakHashMap<Object, Suggestion>();
    private static final WeakHashMap<Object, Home> HOMES = new WeakHashMap<Object, Home>();
    private static final WeakHashMap<Object, Token> CALLBACKS = new WeakHashMap<Object, Token>();
    private static volatile long firstFrame = -1;
    private static volatile long lastInteraction;
    private static volatile long lastPlayback;
    private static boolean optionalScheduled;
    private PerformanceHooks() {}

    private static final class Binding {
        final WeakReference<View> view;
        final Bundle state;
        boolean bound;
        Binding(View v, Bundle b) { view = new WeakReference<View>(v); state = b; }
    }
    private static final class Suggestion {
        final PerformancePolicy.Generation generation = new PerformancePolicy.Generation();
        Runnable pending;
    }
    private static final class Home {
        final PerformancePolicy.Generation generation = new PerformancePolicy.Generation();
        final PerformancePolicy.RefreshWindow refresh = new PerformancePolicy.RefreshWindow();
        Object pendingResponse;
        boolean hasPendingResponse;
        Runnable pending;
    }
    private static final class Token {
        final WeakReference<Object> owner;
        final long generation;
        final boolean home;
        final Object state;
        Token(Object o, long g, boolean h, Object s) { owner = new WeakReference<Object>(o); generation=g; home=h; state=s; }
    }
    private static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        return ReflectAccess.method(owner.getClass(),name,types).invoke(owner,args);
    }
    private static Object call(Object owner, String name) throws Exception { return call(owner,name,new Class<?>[0]); }
    private static void warn(String name, Exception e) { Log.w("KwPerformance",name,e); }

    public static void deferOptional(final Object manager, final Application app) {
        if (Looper.myLooper()!=Looper.getMainLooper()) { MAIN.post(new Runnable() { public void run() { deferOptional(manager,app); }}); return; }
        if (optionalScheduled) return;
        optionalScheduled=true;
        final long requested=SystemClock.uptimeMillis();
        MAIN.post(new Runnable() {
            public void run() {
                long delay=PerformancePolicy.optionalDelay(SystemClock.uptimeMillis(),requested,firstFrame,lastInteraction);
                if (delay>0) { MAIN.postDelayed(this,Math.min(delay,1000)); return; }
                try { call(manager,"leanInitNow",new Class<?>[]{Application.class},app); }
                catch (Exception e) { warn("optional initialization",e); }
            }
        });
    }
    public static void homeCreated(Object fragment) {
        if (firstFrame>=0) return;
        try {
            final View root=(View)call(fragment,"getView");
            if (root==null) return;
            final ViewTreeObserver observer=root.getViewTreeObserver();
            observer.addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                boolean seen;
                public void onDraw() {
                    if (seen) return;
                    seen=true;
                    if (firstFrame<0) firstFrame=SystemClock.uptimeMillis();
                    final ViewTreeObserver.OnDrawListener self=this;
                    MAIN.post(new Runnable() { public void run() {
                        if (observer.isAlive()) observer.removeOnDrawListener(self);
                    }});
                }
            });
        } catch (Exception e) { warn("first frame",e); }
    }
    public static void interaction() { lastInteraction=SystemClock.uptimeMillis(); }
    public static void playbackCommand() {
        interaction();
        lastPlayback=SystemClock.uptimeMillis();
        if (Looper.myLooper()!=Looper.getMainLooper()) { MAIN.post(new Runnable() { public void run() { cancelAllSuggestions(); }}); }
        else cancelAllSuggestions();
    }
    private static void cancelAllSuggestions() {
        for (Object owner : new ArrayList<Object>(SUGGESTIONS.keySet())) if (owner!=null) cancelSuggestions(owner);
    }

    public static void bindWhenVisible(Object fragment, View view, Bundle state) {
        BINDINGS.put(fragment,new Binding(view,state));
        try { if ((Boolean)call(fragment,"getUserVisibleHint")) ensureBound(fragment); }
        catch (Exception e) { throw new IllegalStateException("Deferred page visibility",e); }
    }
    public static void ensureBound(Object fragment) {
        Binding binding=BINDINGS.get(fragment);
        if (binding==null || binding.bound) return;
        View view=binding.view.get();
        if (view==null) return;
        binding.bound=true;
        try { call(fragment,"leanBindView",new Class<?>[]{View.class,Bundle.class},view,binding.state); }
        catch (Exception e) { binding.bound=false; throw new IllegalStateException("Deferred page binding",e); }
    }
    private static Object presenter(Object fragment) throws Exception {
        return ReflectAccess.declaredField(fragment.getClass(),"D").get(fragment);
    }
    public static void pageVisibility(Object fragment, boolean visible) {
        if (!visible) return;
        ensureBound(fragment);
        try {
            Object owner=presenter(fragment);
            Home home=HOMES.get(owner);
            if (home!=null && home.hasPendingResponse) {
                Object response=home.pendingResponse;home.pendingResponse=null;home.hasPendingResponse=false;
                call(owner,"leanApplyHomeResponse",new Class<?>[]{Class.forName("m.c")},response);
            }
        } catch (NoSuchFieldException ignored) { /* Non-MVP page. */ }
        catch (Exception e) { warn("cached home response",e); }
    }
    private static void recommendationMotion(Object fragment, boolean visible) {
        try {
            Object view=ReflectAccess.declaredField(fragment.getClass(),"F").get(fragment);
            if (view!=null) call(view,visible?"H":"G");
        } catch (Exception e) { warn("recommendation visibility",e); }
    }
    public static void hideRecommendation(Object fragment) { recommendationMotion(fragment,false); }
    public static void showRecommendation(Object fragment) {
        try {
            boolean visible=(Boolean)call(fragment,"getUserVisibleHint");
            boolean resumed=(Boolean)call(fragment,"isResumed");
            recommendationMotion(fragment,visible && resumed);
        } catch (Exception e) { warn("recommendation lifecycle",e); }
    }
    public static void releasePage(Object fragment) {
        BINDINGS.remove(fragment);
        try {
            Object owner=presenter(fragment);
            Home home=HOMES.remove(owner);
            if (home!=null && home.pending!=null) MAIN.removeCallbacks(home.pending);
            if (SUGGESTIONS.containsKey(owner)) { cancelSuggestions(owner);SUGGESTIONS.remove(owner); }
        } catch (NoSuchFieldException ignored) { }
        catch (Exception e) { warn("page cleanup",e); }
    }

    public static void suggest(Object owner, final String text) {
        Suggestion state=SUGGESTIONS.get(owner);
        if (state==null) { state=new Suggestion();SUGGESTIONS.put(owner,state); }
        cancelSuggestions(owner);
        final long generation=state.generation.current();
        if (text==null || text.trim().isEmpty()) {
            try { call(owner,"leanClearSuggestions"); } catch (Exception e) { warn("clear suggestions",e); }
            return;
        }
        final WeakReference<Object> weak=new WeakReference<Object>(owner);
        final Suggestion current=state;
        current.pending=new Runnable() { public void run() {
            Object target=weak.get();
            if (target==null || SUGGESTIONS.get(target)!=current || !current.generation.accepts(generation)) return;
            current.pending=null;
            try { call(target,"leanSuggestNow",new Class<?>[]{String.class},text); }
            catch (Exception e) { warn("suggestions",e); }
        }};
        MAIN.postDelayed(current.pending,PerformancePolicy.SUGGEST_DELAY_MS);
    }
    public static void cancelSuggestions(Object owner) {
        Suggestion state=SUGGESTIONS.get(owner);
        if (state==null) return;
        state.generation.advance();
        if (state.pending!=null) { MAIN.removeCallbacks(state.pending);state.pending=null; }
        try { call(owner,"leanCancelSuggestion"); } catch (Exception e) { warn("cancel suggestions",e); }
    }
    public static void registerSuggestion(Object callback,Object owner) {
        Suggestion state=SUGGESTIONS.get(owner);
        CALLBACKS.put(callback,new Token(owner,state==null?-1:state.generation.current(),false,state));
    }
    public static void registerHome(Object callback,Object owner) {
        Home state=HOMES.get(owner);
        CALLBACKS.put(callback,new Token(owner,state==null?-1:state.generation.current(),true,state));
    }
    public static boolean acceptCallback(Object callback) {
        Token token=CALLBACKS.remove(callback);
        if (token==null) return false;
        Object owner=token.owner.get();
        if (owner==null) return false;
        if (token.home) { Home h=HOMES.get(owner);return h!=null && h==token.state && h.generation.accepts(token.generation); }
        Suggestion s=SUGGESTIONS.get(owner);return s!=null && s==token.state && s.generation.accepts(token.generation);
    }

    public static void refreshHome(Object owner,final boolean force) {
        Home home=HOMES.get(owner);
        if (home==null) { home=new Home();HOMES.put(owner,home); }
        if (!home.refresh.start(SystemClock.uptimeMillis(),force)) return;
        final Home state=home;
        final long generation=state.generation.advance();
        final WeakReference<Object> weak=new WeakReference<Object>(owner);
        state.pending=new Runnable() { public void run() {
            Object target=weak.get();
            if (target==null || HOMES.get(target)!=state || !state.generation.accepts(generation)) return;
            long delay=lastPlayback+1200-SystemClock.uptimeMillis();
            if (delay>0) { MAIN.postDelayed(this,delay);return; }
            state.pending=null;
            try { call(target,"leanRefreshNow",new Class<?>[]{boolean.class},force); }
            catch (Exception e) { state.refresh.complete(SystemClock.uptimeMillis(),false);warn("home refresh",e); }
        }};
        MAIN.post(state.pending);
    }
    public static void homeResponse(final Object owner,final Object response) {
        if (Looper.myLooper()!=Looper.getMainLooper()) { MAIN.post(new Runnable() { public void run() { homeResponse(owner,response); }});return; }
        Home home=HOMES.get(owner);
        if (home==null) return;
        boolean success=false;
        try { success=response!=null && (Boolean)call(response,"o") && call(response,"c")!=null; }
        catch (Exception e) { warn("home response status",e); }
        boolean again=home.refresh.complete(SystemClock.uptimeMillis(),success);
        try {
            Object page=call(owner,"n");
            if (page!=null && (Boolean)call(page,"getUserVisibleHint")) {
                call(owner,"leanApplyHomeResponse",new Class<?>[]{Class.forName("m.c")},response);
            } else { home.pendingResponse=response;home.hasPendingResponse=true; }
        } catch (Exception e) { warn("home response",e); }
        if (again) refreshHome(owner,true);
    }
    public static void boundCover(Object fragment) {
        try {
            Object options=ReflectAccess.declaredField(fragment.getClass(),"z").get(fragment);
            View view=(View)ReflectAccess.declaredField(fragment.getClass(),"H").get(fragment);
            if (options==null) return;
            int[] size=PerformancePolicy.artworkSize(view==null?0:view.getWidth(),view==null?0:view.getHeight());
            call(options,"p",new Class<?>[]{int.class,int.class},size[0],size[1]);
        } catch (Exception e) { warn("artwork bounds",e); }
    }
}
