#!/usr/bin/env python3
"""Apply version-specific smali/resource edits to a NEW copy of the decoded APK."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from version import VERSION_NAME, VERSION_CODE, APPLICATION_ID

ROOT = Path(__file__).resolve().parent.parent
BASE = Path(sys.argv[2]).resolve()
DEST = Path(sys.argv[1]).resolve()
if DEST.exists():
    raise SystemExit("Destination already exists; refusing to overwrite")
shutil.copytree(BASE, DEST)
edits = []

def file(relative):
    hits = [p / relative for p in DEST.glob("smali*") if (p / relative).exists()]
    assert len(hits) == 1, (relative, hits)
    return hits[0]

def change(relative, signature, body, locals=1):
    p = file(relative)
    text = p.read_text()
    pattern = re.compile(r"(?m)^\.method ([^\n]* " + re.escape(signature) + r")\n.*?^\.end method", re.S)
    match = pattern.search(text)
    assert match, (relative, signature)
    # Preserve access flags and method descriptor, discard obsolete annotations/debug/local frames.
    replacement = ".method " + match.group(1) + f"\n    .locals {locals}\n\n" + body.strip() + "\n.end method"
    p.write_text(text[:match.start()] + replacement + text[match.end():])
    edits.append({"class": relative, "method": signature, "kind": "replace"})

def inject(relative, signature, code, at_end=False):
    p = file(relative); text = p.read_text()
    pattern = re.compile(r"(?m)^\.method [^\n]* " + re.escape(signature) + r"\n.*?^\.end method", re.S)
    m = pattern.search(text); assert m, (relative, signature)
    value = m.group()
    if at_end:
        assert "    return-void" in value
        value = value.replace("    return-void", code + "\n    return-void")
    else:
        value, count = re.subn(r"(    \.(?:locals|registers) \d+\n)", lambda x: x[1] + "\n" + code + "\n", value, count=1)
        assert count == 1
    p.write_text(text[:m.start()] + value + text[m.end():])
    edits.append({"class": relative, "method": signature, "kind": "inject"})

VOID = "    return-void"
FALSE = "    const/4 v0, 0x0\n    return v0"
TRUE = "    const/4 v0, 0x1\n    return v0"

# Open the main screen through the original no-ad completion state, without ad presenters.
change("cn/kuwo/mod/ad/a.smali", "e()V", VOID)
change("cn/kuwo/mod/ad/a.smali", "start()V", """
    const/4 v0, 0x0
    sget-object v1, Lcn/kuwo/mod/ad/ISplashAdMgr$OnSplashAdShowListener$State;->e:Lcn/kuwo/mod/ad/ISplashAdMgr$OnSplashAdShowListener$State;
    invoke-direct {p0, v0, v1}, Lcn/kuwo/mod/ad/a;->k(Lg6/c;Lcn/kuwo/mod/ad/ISplashAdMgr$OnSplashAdShowListener$State;)V
    return-void
""", 2)

# Promotion requests only: leave free-mode accounting, entitlement and spectrum policy APIs alone.
for sig, param in [("a(ILw8/d;)V", "p1"), ("e(Lw8/d;)V", "p0"), ("f(ILw8/d;)V", "p1"), ("g(ILw8/d;)V", "p1"), ("h(Lw8/d;)V", "p0")]:
    change("f3/c.smali", sig, f"    invoke-static {{{param}}}, Lcn/kuwo/bridge/LeanHooks;->noPromotion(Ljava/lang/Object;)V\n    return-void", 0)
change("cn/kuwo/commercialization/BannerRecyclerView.smali", "p(Le3/e;Ljava/util/List;)V", "    invoke-static {p0}, Lcn/kuwo/bridge/LeanHooks;->hide(Landroid/view/View;)V\n    return-void", 0)
change("e3/b.smali", "i(Lcn/kuwo/commercialization/BannerRecyclerView;Landroid/content/Context;Lcn/kuwo/commercialization/AdType;Ljava/util/List;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/LeanHooks;->hide(Landroid/view/View;)V\n    return-void", 0)
change("e3/b.smali", "j(Lcn/kuwo/commercialization/AdType;Landroid/content/Context;Lg3/a;Landroid/content/DialogInterface$OnDismissListener;)Z", FALSE)

# User action/client statistics, service-level and advertising reports. Keep local diagnostics.
for sig, body in [("c()V", VOID), ("d(Lcn/kuwo/base/log/sevicelevel/bean/a;)Z", TRUE), ("e()V", VOID)]:
    change("b1/b.smali", sig, body)
change("b1/c.smali", "a(Lcn/kuwo/base/log/sevicelevel/bean/a;)Z", TRUE)
for sig, body in [("c()V", VOID), ("d(Ljava/lang/String;Lcn/kuwo/base/http/HttpResult;Lcn/kuwo/base/bean/Music;)Z", FALSE), ("e(Ljava/lang/String;Ljava/lang/String;I)Z", FALSE), ("f()Z", FALSE)]:
    change("cn/kuwo/base/log/a.smali", sig, body)
for sig in ["c(Ljava/io/File;)Z", "d(Ljava/lang/String;Z)Z", "f(Ljava/io/File;)Z", "g(Ljava/lang/String;Z)Z"]:
    change("a1/m.smali", sig, FALSE)
change("a1/m.smali", "e()V", VOID)
for sig, body in [("h(Ljava/lang/String;Ljava/lang/String;)Z", TRUE), ("j(Lv6/a$d;)Z", TRUE), ("n()V", VOID), ("p(Ljava/lang/String;Ljava/lang/String;)Z", TRUE), ("q(Ljava/lang/String;Ljava/lang/String;)V", VOID)]:
    change("v6/a.smali", sig, body)
change("s1/b.smali", "c()V", VOID)
change("s1/b.smali", "j()V", VOID)
change("com/tencent/raft/measure/report/ATTAReporter.smali", "doPostBatchReport(Lorg/json/JSONArray;)V", VOID)
for sig, body in [("reportToATTA(Ljava/util/Map;)Z", FALSE), ("reportRecordToATTA(Lcom/tencent/rfix/lib/atta/ATTARecord;)Z", FALSE), ("reportRecordInDB()V", VOID), ("writeRecordToDB(Ljava/util/Map;)V", VOID)]:
    change("com/tencent/rfix/lib/atta/RFixATTAReporter.smali", sig, body)

# Remove entire tab destinations before the pager is constructed; keep indices contiguous.
inject("cn/kuwo/kwmusiccar/ui/MainFragment.smali", "<clinit>()V", """    sget-object v0, Lcn/kuwo/kwmusiccar/ui/MainFragment;->n0:Ljava/util/Map;
    invoke-static {v0}, Lcn/kuwo/bridge/LeanHooks;->pruneTabs(Ljava/util/Map;)V
    const/4 v0, -0x1
    sput v0, Lcn/kuwo/kwmusiccar/ui/MainFragment;->k0:I
    const/4 v0, -0x2
    sput v0, Lcn/kuwo/kwmusiccar/ui/MainFragment;->l0:I
    const/4 v0, 0x2
    sput v0, Lcn/kuwo/kwmusiccar/ui/MainFragment;->m0:I""", True)
inject("w3/b.smali", "<clinit>()V", "    sget-object v0, Lw3/b;->a:Ljava/util/Map;\n    invoke-static {v0}, Lcn/kuwo/bridge/LeanHooks;->pruneDestinations(Ljava/util/Map;)V", True)
inject("cn/kuwo/kwmusiccar/ui/fragment/SearchResultFragment.smali", "<init>()V", "    iget-object v0, p0, Lcn/kuwo/kwmusiccar/ui/fragment/SearchResultFragment;->G:Ljava/util/List;\n    invoke-static {v0}, Lcn/kuwo/bridge/LeanHooks;->pruneSearchTabs(Ljava/util/List;)V", True)
inject("f5/d.smali", "q(Ljava/lang/Class;Landroid/os/Bundle;Landroidx/navigation/NavOptions;)V", """    invoke-static {p0}, Lcn/kuwo/bridge/LeanHooks;->blocked(Ljava/lang/Class;)Z
    move-result v0
    if-eqz v0, :lean_route_allowed
    return-void
    :lean_route_allowed""")

# Dynamic mine/settings rows must be removed from data, rather than hidden RecyclerView slots.
inject("b4/x.smali", "l(Ljava/util/List;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/LeanHooks;->filterMine(Ljava/util/List;)Ljava/util/List;\n    move-result-object p1")
inject("cn/kuwo/kwmusiccar/ui/adapter/MineAdapter.smali", "j(Ljava/util/List;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/LeanHooks;->filterSettings(Ljava/util/List;)Ljava/util/List;\n    move-result-object p1")

# Disable backing initializers/render loops and explicit offline-download task creation.
change("cn/kuwo/mod/karaoke/KaraokeManger.smali", "F()V", VOID)
change("c7/i.smali", "e()V", VOID)
change("c7/i.smali", "d()Z", FALSE)
change("c7/i.smali", "f()Z", FALSE)
for sig in ["G()Z", "H()Z", "I()Z", "U()Z", "f()Z", "i()Z", "j(Lcn/kuwo/base/bean/Music;)Z", "k(Lcn/kuwo/base/bean/Music;)Z", "l(Lcn/kuwo/base/bean/Music;)Z"]:
    change("cn/kuwo/mod/karaoke/KaraokeManger.smali", sig, FALSE)
change("cn/kuwo/ui/spectrum/c.smali", "h()Z", FALSE)
for sig in ["l()V", "u([D[D)V"]: change("cn/kuwo/ui/spectrum/c.smali", sig, VOID)
for sig in ["l()V", "start()V", "r([D)V"]: change("cn/kuwo/ui/spectrum/SpectrumView.smali", sig, VOID)
change("cn/kuwo/kwmusiccar/ui/nowplaying/NowPlayingFragment.smali", "X6()V", VOID)
for sig in ["playVideo(Lcn/kuwo/base/bean/video/Video;)V", "prepare(Ljava/lang/String;)V", "videoStart()V", "continuePlay()V", "reLoad(ZJ)V"]:
    change("cn/kuwo/base/view/KwVideoPlayer.smali", sig, VOID)
change("k8/c.smali", "f()I", "    const/4 v0, 0x1\n    return v0")
# This is the setting for permanent listen-and-save, not the streaming cache.
change("k8/c.smali", "m(Z)V", VOID)
change("k8/c.smali", "d()Z", FALSE)
for sig in ["R2(Lcn/kuwo/base/bean/Music;Lcn/kuwo/base/bean/MusicQuality;ZLjava/lang/Boolean;)V", "m(Lcn/kuwo/base/bean/Music;Lcn/kuwo/base/bean/MusicQuality;Z)V", "C4(Ln7/a;Z)V", "H4(Ln7/a;)V", "I4()V"]:
    change("cn/kuwo/mod/download/a.smali", sig, VOID)
change("cn/kuwo/mod/download/a.smali", "W3(Lcn/kuwo/base/bean/Music;Lcn/kuwo/base/bean/MusicQuality;)I", "    const/4 v0, -0x1\n    return v0")
inject("cn/kuwo/kwmusiccar/KwApp.smali", "initSelf()V", "    invoke-static {}, Lcn/kuwo/bridge/LeanHooks;->configure()V")

# Remove old SDK paths for deleted capabilities without changing the music control contract.
for sig in ["a()Lh/d;", "b()Lh/d;", "c()Lh/d;", "e(Ljava/util/Map;)Lh/d;", "g(Ljava/util/Map;)Lh/d;", "h(Ljava/util/Map;)Lh/d;", "o()Lh/d;", "p()Lh/d;", "q()Lh/d;", "r(Ljava/util/Map;)Lh/d;", "s()Lh/d;", "u(Ljava/util/Map;)Lh/d;", "w(Ljava/util/Map;)Lh/d;", "x(Ljava/util/Map;)Lh/d;"]:
    change("h/b.smali", sig, "    const-string v0, \"Capability removed in lean build\"\n    invoke-static {v0}, Lh/d;->h(Ljava/lang/String;)Lh/d;\n    move-result-object v0\n    return-object v0")

# Host publications feed the new observer API; callbacks never perform network work.
inject("android/support/v4/media/session/MediaSessionCompat.smali", "j(Landroid/os/Bundle;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/BridgeProvider;->onLyrics(Landroid/os/Bundle;)V")
for sig in ["l(Landroid/support/v4/media/MediaMetadataCompat;)V", "m(Landroid/support/v4/media/session/PlaybackStateCompat;)V", "n(Ljava/util/List;)V"]:
    inject("android/support/v4/media/session/MediaSessionCompat.smali", sig, "    invoke-static {}, Lcn/kuwo/bridge/BridgeProvider;->onStateChanged()V", True)
change("cn/kuwo/mod/mediaSession/KwMediaSessionService.smali", "l()Ljava/util/List;", "    invoke-static {}, Lcn/kuwo/bridge/BridgeProvider;->legacyMediaItems()Ljava/util/List;\n    move-result-object v0\n    return-object v0")
change("cn/kuwo/mod/mediaSession/KwMediaSessionService.smali", "B(I)V", "    iget-object v0, p0, Lcn/kuwo/mod/mediaSession/KwMediaSessionService;->b:Landroid/support/v4/media/session/MediaSessionCompat;\n    invoke-static {v0, p1}, Lcn/kuwo/bridge/LeanHooks;->syncModes(Ljava/lang/Object;I)V\n    return-void")
change("cn/kuwo/mod/mediaSession/KwMediaSessionService$d.smali", "y(I)V", """    if-nez p1, :lean_shuffle_on
    const-string v0, "MEDIA_ORDER"
    goto :lean_shuffle_apply
    :lean_shuffle_on
    const/4 v0, 0x1
    if-ne p1, v0, :lean_shuffle_return
    const-string v0, "MEDIA_RANDOM"
    :lean_shuffle_apply
    invoke-static {}, Lcn/kuwo/mod/playcontrol/PlayerStateManager;->u0()Lcn/kuwo/mod/playcontrol/PlayerStateManager;
    move-result-object v1
    invoke-virtual {v1, v0}, Lcn/kuwo/mod/playcontrol/PlayerStateManager;->i1(Ljava/lang/String;)Z
    :lean_shuffle_return
    return-void
""", 2)

# Hide resource views in every orientation; preserve IDs so existing code cannot null-dereference.
hidden = {"fra_item_radio", "fra_item_zhenxuan", "iv_top_relax", "iv_sound_efftct", "iv_mv", "iv_download", "iv_spectrum", "ll_quality", "ll_effect", "ll_karaoke", "ll_karaoke_open", "img_karaoke", "layout_karaoke_guide", "try_karaoke_guid_bg", "vip_store", "layout_business", "layout_ad", "lav_zp", "iv_vinyl_cover_layout", "iv_holiday_sk_gb", "mini_lyric_vip", "img_quality_icon", "img_db"}
hidden.update({"iv_top_relax_deep", "ll_quality_effect", "layout_karaoke_small", "layout_karaoke_guide_big", "karaoke_lotti", "ll_close_karaoke_animation", "rl_karaoke_animation", "play_download_tv", "text_download", "ll_quality_traffic", "rl_set_quality_traffic"})
A = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
ET.register_namespace("app", "http://schemas.android.com/apk/res-auto")
for p in DEST.glob("res/layout*/*.xml"):
    tree = ET.parse(p); changed = []
    for node in tree.iter():
        name = node.get(A + "id", "").split("/")[-1]
        if name in hidden:
            node.set(A + "visibility", "gone"); node.set(A + "layout_height", "0dp")
            if A + "layout_weight" in node.attrib: node.set(A + "layout_weight", "0")
            changed.append(name)
    if changed:
        tree.write(p, encoding="utf-8", xml_declaration=True)
        edits.append({"resource": str(p.relative_to(DEST)), "hidden": changed})

# Compact only the home tab row; leave the search and action modules anchored.
# Retain every tab ID because MainFragment still resolves the hidden tab views.
tab_ids = ['fra_item_mine', 'fra_item_recomment', 'fra_item_music_lib']
for p in DEST.glob('res/layout*/fragment_main_top*.xml'):
    tree = ET.parse(p)
    nodes = {n.get(A+'id', '').split('/')[-1]: n for n in tree.iter()}
    if not all(name in nodes for name in tab_ids):
        continue
    parents = {child: parent for parent in tree.iter() for child in parent}
    row = parents[nodes[tab_ids[0]]]
    assert all(parents[nodes[name]] is row for name in tab_ids)
    # Portrait had four weighted spacers; landscape inherited weights from style.
    for child in list(row):
        if child.tag == 'Space': row.remove(child)
    row.set(A+'gravity', 'center')
    row.set(A+'layout_width', 'wrap_content')
    row.set(A+'layout_gravity', 'center_horizontal')
    row.set(A+'paddingEnd', '0dp')
    for name in tab_ids:
        tab = nodes[name]
        tab.set(A+'layout_width', 'wrap_content')
        tab.set(A+'layout_weight', '0')
        tab.set(A+'minWidth', '80dp')
        tab.set(A+'paddingStart', '12dp')
        tab.set(A+'paddingEnd', '12dp')
        tab.set(A+'layout_marginStart', '0dp' if name == tab_ids[0] else '8dp')
        text = nodes[name.replace('fra_', 'tv_', 1)]
        text.set(A+'layout_width', 'wrap_content')
        text.set(A+'layout_gravity', 'center')
        text.set(A+'gravity', 'center')
    if p.name == 'fragment_main_top.xml':
        # A wrap-content RelativeLayout cannot place an avatar at negative X.
        avatar = nodes['iv_user_header']
        avatar.attrib.pop(A+'layout_toStartOf', None)
        avatar.set(A+'layout_alignParentStart', 'true')
        nodes['tv_item_mine'].set(A+'layout_toEndOf', '@id/iv_user_header')
    tree.write(p, encoding='utf-8', xml_declaration=True)
    edits.append({'resource': str(p.relative_to(DEST)), 'compact_home_tabs': tab_ids,
                  'tab_gap_dp': 8, 'weighted_spacers': 0})

# Apply the same small view policy after binding, when code may set visibility again.
inject("cn/kuwo/kwmusiccar/ui/base/BaseKuwoFragment.smali", "onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/LeanHooks;->installUi(Landroid/view/View;)V", True)
inject("b4/x.smali", "c(Lc4/b$b;I)V", "    invoke-static {p1}, Lcn/kuwo/bridge/LeanHooks;->installHolder(Ljava/lang/Object;)V", True)

from performance_patch import apply_performance
apply_performance(file, change, inject, edits)

# The original update control is hidden and has no click handler. Reuse that row.
inject("cn/kuwo/kwmusiccar/ui/fragment/CheckUpdateFragment.smali", "onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
       "    invoke-static {p1}, Lcn/kuwo/bridge/GithubUpdater;->bindAbout(Landroid/view/View;)V", True)
change("cn/kuwo/kwmusiccar/KwApp.smali", "getVersionName()Ljava/lang/String;",
       f'    const-string v0, "{VERSION_NAME}"\n    return-object v0', 1)
change("cn/kuwo/kwmusiccar/KwApp.smali", "getVersionCode()I",
       f'    const v0, {hex(VERSION_CODE)}\n    return v0', 1)
# Retain RFix's Application proxy, but stop the vendor's remote patch checks.
change("s5/b.smali", "e(Landroid/app/Application;)V", VOID, 0)

# Preserve privacy/intent/account setup and enter the home directly after it.
welcome = "cn/kuwo/kwmusiccar/ui/WelcomeActivity.smali"
text = file(welcome).read_text()
method = re.search(r"(?m)^\.method private H\(\)V\n.*?^\.end method", text, re.S)
assert method
body = method.group()
start = body.index("    const v2, 0x7f08038b")
body = body[:start] + '''    const-class v0, Lcn/kuwo/kwmusiccar/ui/MainActivity;
    const-string v2, "home"
    invoke-direct {p0, v0, v2}, Lcn/kuwo/kwmusiccar/ui/WelcomeActivity;->J(Ljava/lang/Class;Ljava/lang/String;)V
    return-void
.end method'''
text = text[:method.start()] + body + text[method.end():]
# Avoid creating the unused splash layout; the original policy dialog remains.
text, count = re.subn(r"    invoke-virtual \{p0, v\d+\}, Landroidx/appcompat/app/AppCompatActivity;->setContentView\(I\)V", "", text)
assert count == 1
file(welcome).write_text(text)
# Remove the fixed 100 ms hop without changing intent forwarding.
text = file(welcome).read_text()
start = text.index("    .line 9\n", text.index(".method private J("))
end = text.index("    .line 13\n", start)
text = text[:start] + "    invoke-direct {p0, v1}, Lcn/kuwo/kwmusiccar/ui/WelcomeActivity;->O(Landroid/content/Intent;)V\n\n" + text[end:]
file(welcome).write_text(text)
edits.append({'class': welcome, 'kind': 'direct_home_without_splash_or_fixed_delay'})

# Preserve the host music service; boot starts it without an Activity or autoplay.
change("cn/kuwo/kwmusiccar/common/startup/BootReceiver.smali", "onReceive(Landroid/content/Context;Landroid/content/Intent;)V", """    invoke-static {p1, p2}, Lcn/kuwo/bridge/SilentStartup;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    return-void""", 0)
change("cn/kuwo/service/MainService.smali", "t()V", """    invoke-static {p0}, Lcn/kuwo/bridge/SilentStartup;->promote(Landroid/app/Service;)V
    return-void""", 0)
change("cn/kuwo/kwmusiccar/util/e.smali", "d()Z", """    invoke-static {}, Lcn/kuwo/bridge/SilentStartup;->enabled()Z
    move-result v0
    return v0""", 1)
change("cn/kuwo/kwmusiccar/util/e.smali", "j(I)V", """    const/4 v0, 0x0
    if-nez p1, :lean_boot_store
    const/4 v0, 0x1
    :lean_boot_store
    invoke-static {v0}, Lcn/kuwo/bridge/SilentStartup;->setEnabled(Z)V
    return-void""", 1)
inject("android/support/v4/media/session/MediaSessionCompat.smali", "l(Landroid/support/v4/media/MediaMetadataCompat;)V", "    invoke-static {p1}, Lcn/kuwo/bridge/BridgeProvider;->onMetadata(Ljava/lang/Object;)V")
# Notify after each exit of the original list mutators, including ordinary UI edits.
for signature in ["N2(Ljava/lang/String;Ljava/util/List;I)I", "T0(Ljava/lang/String;Ljava/util/List;)Z", "U0(Ljava/lang/String;Z)Z", "M1(Lcn/kuwo/base/bean/ListType;Ljava/lang/String;)Lcn/kuwo/base/bean/MusicList;", "O(Ljava/lang/String;Ljava/lang/String;)Z", "F(Ljava/lang/String;Z)Z", "W1(Ljava/lang/String;II)Z"]:
    p=file("ya/f.smali");text=p.read_text()
    match=re.search(r"(?m)^\.method [^\n]* "+re.escape(signature)+r"\n.*?^\.end method",text,re.S)
    assert match,signature
    method,count=re.subn(r"(?m)^(    return(?:-object)? [^\n]+)$", "    invoke-static {}, Lcn/kuwo/bridge/BridgeProvider;->onListsChanged()V\n\\1",match.group())
    assert count>0,signature
    p.write_text(text[:match.start()]+method+text[match.end():])
    edits.append({'class':'ya/f.smali','method':signature,'kind':'list_observer'})

manifest = DEST / "AndroidManifest.xml"
tree = ET.parse(manifest); app = tree.getroot().find("application")
boot = [n for n in app.findall('receiver') if n.get(A+'name')=='cn.kuwo.kwmusiccar.common.startup.BootReceiver']
assert len(boot)==1
boot[0].set(A+'exported','false')
ET.SubElement(boot[0].find('intent-filter'),'action',{A+'name':'android.intent.action.MY_PACKAGE_REPLACED'})
main_service = [n for n in app.findall('service') if n.get(A+'name')=='cn.kuwo.service.MainService']
assert len(main_service)==1
main_service[0].set(A+'foregroundServiceType','mediaPlayback')
main_service[0].set(A+'stopWithTask','false')
ET.SubElement(app, 'activity', {
    A+'name': 'cn.kuwo.bridge.UpdateActivity', A+'exported': 'false',
    A+'configChanges': 'orientation|screenSize|keyboardHidden|uiMode',
    A+'theme': '@android:style/Theme.Material.NoActionBar'})
# This vehicle retains the original package as a system app. Use its already
# established user-app package without renaming Java classes or broadcast actions.
original_package = tree.getroot().get('package')
identities = {original_package: APPLICATION_ID}
for node in tree.getroot().iter():
    authority = node.get(A+'authorities')
    if authority and authority.startswith(original_package+'.'):
        renamed = APPLICATION_ID + authority[len(original_package):]
        identities[authority] = renamed
        node.set(A+'authorities', renamed)
for node in tree.getroot():
    if node.tag in ('permission', 'uses-permission'):
        name = node.get(A+'name', '')
        if name.startswith(original_package+'.'):
            renamed = APPLICATION_ID + name[len(original_package):]
            identities[name] = renamed
            node.set(A+'name', renamed)
for node in app.iter():
    name = node.get(A+'name', '')
    if name.startswith('.'): node.set(A+'name', original_package+name)
tree.getroot().set('package', APPLICATION_ID)
for p in DEST.glob('smali*/**/*.smali'):
    old = p.read_text()
    new = old
    for source, target in identities.items():
        new = new.replace('"'+source+'"', '"'+target+'"')
    if old != new:
        p.write_text(new)
        edits.append({'class': str(p.relative_to(DEST)), 'kind': 'self_package_or_authority_literals'})
edits.append({'manifest_package': APPLICATION_ID, 'internal_classes_retained': original_package,
              'private_authorities': identities, 'bridge_authority_retained': 'cn.kuwo.kwmusiccar.bridge'})
ET.SubElement(app, "provider", {A+"name": "cn.kuwo.bridge.BridgeProvider", A+"authorities": "cn.kuwo.kwmusiccar.bridge", A+"exported": "true", A+"grantUriPermissions": "false"})
queries = tree.getroot().find("queries")
if queries is None: queries = ET.SubElement(tree.getroot(), "queries")
for line in (ROOT / "config/bridge_clients.txt").read_text().splitlines():
    line = line.strip()
    if line and not line.startswith("#"): ET.SubElement(queries, "package", {A+"name": line})
tree.write(manifest, encoding="utf-8", xml_declaration=True)
shutil.copy(ROOT / "config/bridge_clients.txt", DEST / "assets/bridge_clients.txt")
yml = DEST / "apktool.yml"; text = yml.read_text()
text = text.replace('versionCode: 73923', f'versionCode: {VERSION_CODE}').replace('versionName: 7.3.9.23', f'versionName: {VERSION_NAME}')
yml.write_text(text)
(DEST.parent / "patch-manifest.json").write_text(json.dumps(edits, ensure_ascii=False, indent=2) + "\n")
print(f"Applied {len(edits)} version-specific edits to {DEST}")
