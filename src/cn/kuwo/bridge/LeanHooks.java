package cn.kuwo.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.Arrays;

/** Small no-ad completion adapter; preserves business callback contracts. */
public final class LeanHooks {
    private static final WeakHashMap<View, Boolean> HIDDEN_LISTENERS = new WeakHashMap<View, Boolean>();
    private static volatile int[] hiddenIds;
    private static final View.OnLayoutChangeListener KEEP_HIDDEN = new View.OnLayoutChangeListener() {
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { hide(v); }
    };
    private LeanHooks() {}
    public static void noPromotion(final Object callback) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> responseClass = Class.forName("m.c");
                    Object response = responseClass.getConstructor().newInstance();
                    BridgeProvider.invoke(response, "h", new Class<?>[] {int.class}, 204);
                    BridgeProvider.invoke(response, "m", new Class<?>[] {String.class}, "No promotion");
                    BridgeProvider.invoke(callback, "d", new Class<?>[] {responseClass}, response);
                } catch (Exception e) { Log.w("KwLean", "promotion completion", e); }
            }
        });
    }
    public static void hide(View view) {
        if (view == null) return;
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        ViewGroup.LayoutParams p = view.getLayoutParams();
        if (p != null && p.height != 0) { p.height = 0; view.setLayoutParams(p); }
    }

    public static boolean blockedName(String name) {
        return name != null && (name.contains(".homezhenxuan.") || name.contains(".homeradio.")
            || name.contains(".relax.") || name.contains(".soundeffect.") || name.contains(".mv.")
            || name.contains(".karaoke.") || name.contains(".cloud.")
            || name.contains("CloudDriverFragment") || name.contains("Download")
            || name.contains(".ui.fragment.Local") || name.contains("SettingTryQualityFragment")
            || name.contains("SettingQualityFragment") || name.contains("SearchMVResultFragment")
            || name.contains("SearchBookResultFragment") || name.contains(".tingshu."));
    }
    public static boolean blocked(Class<?> type) { return type != null && blockedName(type.getName()); }
    public static void pruneDestinations(Map<?, ?> map) {
        Iterator<?> it = map.keySet().iterator();
        while (it.hasNext()) if (blockedName(String.valueOf(it.next()))) it.remove();
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void pruneTabs(Map map) {
        Object mine = map.get(0), recommend = map.get(1), music = map.get(4);
        map.clear(); map.put(0, mine); map.put(1, recommend); map.put(2, music);
    }
    public static void pruneSearchTabs(List<?> tabs) {
        tabs.remove("视频"); tabs.remove("听书");
    }
    private static boolean removedLabel(String s) {
        return s != null && (s.contains("音质") || s.contains("音效") || s.contains("伴唱")
            || s.contains("动效") || s.contains("下载") || s.contains("云盘")
            || s.contains("本地") || s.contains("边听边存") || s.contains("会员中心")
            || s.contains("全息声音") || s.equals("听吧") || s.equals("甄选"));
    }
    public static List<?> filterMine(List<?> source) {
        ArrayList<Object> out = new ArrayList<Object>();
        if (source == null) return out;
        for (Object item : source) try {
            String label = (String) BridgeProvider.invoke(item, "e");
            Object musicList = BridgeProvider.invoke(item, "d");
            String type = musicList == null ? "" : BridgeProvider.invoke(musicList, "s").toString();
            if (musicList != null) label = (String) BridgeProvider.invoke(musicList, "getName");
            if (!removedLabel(label) && !type.contains("LOCAL") && !type.contains("DOWNLOAD") && !type.contains("CLOUD")) out.add(item);
        } catch (Exception e) { Log.w("KwLean", "unrecognized mine row", e); }
        return out;
    }
    public static List<?> filterSettings(List<?> source) {
        ArrayList<Object> out = new ArrayList<Object>();
        if (source == null) return out;
        try {
            Class<?> blockType = Class.forName("b4.c0");
            for (Object block : source) {
                String name = (String) BridgeProvider.invoke(block, "a");
                List<?> entries = (List<?>) BridgeProvider.invoke(block, "b");
                ArrayList<Object> keep = new ArrayList<Object>();
                for (Object entry : entries) if (!removedLabel((String) BridgeProvider.invoke(entry, "f"))) keep.add(entry);
                if (!keep.isEmpty()) out.add(blockType.getConstructor(String.class, List.class).newInstance(name, keep));
            }
        } catch (Exception e) { Log.w("KwLean", "settings filter", e); }
        return out;
    }
    public static void configure() {
        try {
            Class<?> prefs = Class.forName("v.b");
            String[] disabled = {"key_galaxy_effect_switcher", "key_car_effect_switcher", "key_karaoke_use_try"};
            for (String key : disabled) prefs.getMethod("l", String.class, String.class, boolean.class, boolean.class)
                .invoke(null, "appconfig", key, false, false);
            prefs.getMethod("q", String.class, String.class, String.class, boolean.class)
                .invoke(null, "appconfig", "key_spectrum_id", "pdfmdx0", false);
            Class.forName("h1.a").getMethod("k", String.class, String.class, int.class, boolean.class)
                .invoke(null, "", "music_quality_when_play", 1, false);
            Class.forName("h1.a").getMethod("k", String.class, String.class, int.class, boolean.class)
                .invoke(null, "audioeffect", "effecttype", 0, false);
            Class.forName("h1.a").getMethod("i", String.class, String.class, boolean.class, boolean.class)
                .invoke(null, "sec_super_sound", "key_zp_effect", false, false);
        } catch (Exception e) { Log.w("KwLean", "default configuration", e); }
    }
    public static void installUi(View root) {
        if (root == null) return;
        int[] ids = hiddenIds;
        if (ids == null) {
            synchronized (HIDDEN_LISTENERS) {
                ids = hiddenIds;
                if (ids == null) hiddenIds = ids = resolveIds(root);
            }
        }
        installViews(root, ids);
    }
    private static int[] resolveIds(View root) {
        String[] names = {"fra_item_radio", "fra_item_zhenxuan", "iv_top_relax", "iv_top_relax_deep", "iv_sound_efftct",
            "iv_mv", "iv_download", "iv_spectrum", "ll_quality", "ll_effect", "ll_karaoke",
            "ll_karaoke_open", "img_karaoke", "layout_karaoke_guide", "try_karaoke_guid_bg",
            "vip_store", "layout_business", "layout_ad", "lav_zp",
            "iv_vinyl_cover_layout", "iv_holiday_sk_gb", "mini_lyric_vip", "img_quality_icon", "img_db",
            "ll_quality_effect", "layout_karaoke_small", "layout_karaoke_guide_big", "karaoke_lotti",
            "ll_close_karaoke_animation", "rl_karaoke_animation", "play_download_tv", "text_download",
            "ll_quality_traffic", "rl_set_quality_traffic"};
        int[] ids = new int[names.length];
        int count = 0;
        for (String name : names) {
            int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            if (id == 0) id = root.getResources().getIdentifier(name, "id", "cn.kuwo.kwmusiccar");
            if (id != 0) ids[count++] = id;
        }
        ids = Arrays.copyOf(ids, count);
        Arrays.sort(ids);
        return ids;
    }
    private static void installViews(View view, int[] ids) {
        if (Arrays.binarySearch(ids, view.getId()) >= 0) {
            hide(view);
            if (!HIDDEN_LISTENERS.containsKey(view)) {
                HIDDEN_LISTENERS.put(view, true);
                view.addOnLayoutChangeListener(KEEP_HIDDEN);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) installViews(group.getChildAt(i), ids);
        }
    }
    public static void installHolder(Object holder) {
        try { installUi((View) ReflectAccess.field(holder.getClass(), "itemView").get(holder)); }
        catch (Exception e) { Log.w("KwLean", "row view", e); }
    }
    public static void syncModes(Object session, int mode) {
        if (session == null) return;
        try {
            BridgeProvider.invoke(session, "r", new Class<?>[] {int.class}, mode == 3 ? 1 : 0);
            BridgeProvider.invoke(session, "p", new Class<?>[] {int.class}, mode == 0 ? 1 : mode == 2 ? 2 : 0);
        } catch (Exception e) { Log.w("KwLean", "mode publication", e); }
    }
}
