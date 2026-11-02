#!/usr/bin/env python3
"""Offline artifact, reflection-symbol and feature-removal checks; no runtime claims."""
from pathlib import Path
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from version import VERSION_NAME, VERSION_CODE, APPLICATION_ID

ROOT = Path(__file__).resolve().parent.parent
BUILD = Path(sys.argv[1]).resolve()
META = json.loads((BUILD/'build-result.json').read_text())
APK = Path(META['apk'])
AAPT = str(Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'build-tools/36.1.0/aapt2')
checks = []

def check(name, value):
    if not value: raise AssertionError(name)
    checks.append(name)

def read_uleb(b, off):
    result = shift = 0
    while True:
        n = b[off]; off += 1
        result |= (n & 127) << shift
        if not n & 128: return result, off
        shift += 7

def dex_symbols(b):
    check('DEX magic '+b[4:7].decode(), b.startswith(b'dex\n'))
    string_n, string_o = struct.unpack_from('<II', b, 56)
    strings = []
    for i in range(string_n):
        off = struct.unpack_from('<I', b, string_o+4*i)[0]
        _, off = read_uleb(b, off)
        end = b.index(0, off)
        strings.append(b[off:end].decode('utf-8', errors='replace'))
    type_n, type_o = struct.unpack_from('<II', b, 64)
    types = [strings[struct.unpack_from('<I', b, type_o+4*i)[0]] for i in range(type_n)]
    proto_n, proto_o = struct.unpack_from('<II', b, 72)
    protos = []
    for i in range(proto_n):
        _, ret, params = struct.unpack_from('<III', b, proto_o+12*i)
        args = []
        if params:
            n = struct.unpack_from('<I', b, params)[0]
            args = [types[struct.unpack_from('<H', b, params+4+2*j)[0]] for j in range(n)]
        protos.append('('+''.join(args)+')'+types[ret])
    method_n, method_o = struct.unpack_from('<II', b, 88)
    methods = []
    for i in range(method_n):
        cls, proto, name = struct.unpack_from('<HHI', b, method_o+8*i)
        methods.append(types[cls]+'->'+strings[name]+protos[proto])
    field_n, field_o = struct.unpack_from('<II', b, 80)
    fields = []
    for i in range(field_n):
        cls, typ, name = struct.unpack_from('<HHI', b, field_o+8*i)
        fields.append(types[cls]+'->'+strings[name]+':'+types[typ])
    class_n, class_o = struct.unpack_from('<II', b, 96)
    declared_m, declared_f, classes = set(), set(), set()
    for i in range(class_n):
        cls, _, _, _, _, _, data, _ = struct.unpack_from('<IIIIIIII', b, class_o+32*i)
        classes.add(types[cls])
        if not data: continue
        sf, data = read_uleb(b, data); inf, data = read_uleb(b, data)
        dm, data = read_uleb(b, data); vm, data = read_uleb(b, data)
        for n in [sf, inf]:
            index = 0
            for _ in range(n):
                diff, data = read_uleb(b, data); _, data = read_uleb(b, data)
                index += diff; declared_f.add(fields[index])
        for n in [dm, vm]:
            index = 0
            for _ in range(n):
                diff, data = read_uleb(b, data); _, data = read_uleb(b, data); _, data = read_uleb(b, data)
                index += diff; declared_m.add(methods[index])
    return classes, declared_m, declared_f

classes, methods, fields = set(), set(), set()
with zipfile.ZipFile(APK) as z, zipfile.ZipFile(META['base_apk']) as original:
    check('ZIP CRC', z.testzip() is None)
    check('four DEX files', sorted(n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)) == ['classes.dex','classes2.dex','classes3.dex','classes4.dex'])
    for name in z.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            c,m,f = dex_symbols(z.read(name)); classes.update(c); methods.update(m); fields.update(f)
    native = [n for n in original.namelist() if n.endswith('.so')]
    check('all 81 native libraries preserved byte-for-byte', len(native) == 81 and all(z.read(n) == original.read(n) for n in native))
    check('default caller com.cabindeck', b'com.cabindeck' in z.read('assets/bridge_clients.txt'))

required = [
 'Lcn/kuwo/kwmusiccar/KwApp;->getInstance()Lcn/kuwo/kwmusiccar/KwApp;',
 'Lcn/kuwo/kwmusiccar/KwApp;->isAppPrivacyAgreed()Z',
 'Lcn/kuwo/service/MainService;->q()Z',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->u0()Lcn/kuwo/mod/playcontrol/PlayerStateManager;',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->y0()Lcn/kuwo/mod/playcontrol/PlayerState;',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->v0()Lcn/kuwo/base/bean/Music;',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->w0()Ljava/util/List;',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->t0()I',
 'Lcn/kuwo/mod/playcontrol/PlayerStateManager;->i1(Ljava/lang/String;)Z',
 'Lcn/kuwo/mod/playcontrol/PlayerState;->p()Lcn/kuwo/mod/playcontrol/PlayerState$Status;',
 'Lcn/kuwo/mod/playcontrol/PlayerState;->q()I',
 'Lc6/b;->j()Lcn/kuwo/mod/list/IListMgr;',
 'Lc6/b;->n()Lg8/e;',
 'Lg8/e;->getDuration()I', 'Lg8/e;->seek(I)V', 'Lg8/e;->o1()I', 'Lg8/e;->V()Lcn/kuwo/base/bean/MusicList;',
 'Lcn/kuwo/mod/list/IListMgr;->F1()Ljava/util/Collection;',
 'Lcn/kuwo/mod/list/IListMgr;->A4(Ljava/lang/String;)Lcn/kuwo/base/bean/MusicList;',
 'Lcn/kuwo/base/bean/MusicList;->z()Ljava/util/List;',
 'Lcn/kuwo/base/bean/MusicList;->s()Lcn/kuwo/base/bean/ListType;',
 'Lcn/kuwo/base/bean/MusicList;->getName()Ljava/lang/String;',
 'Lq5/r0;->A()Lq5/r0;', 'Lq5/r0;->B0(Ljava/util/List;I)V',
 'Lcn/kuwo/mod/mediaSession/KwMediaSessionService;->j()Lcn/kuwo/mod/mediaSession/KwMediaSessionService;',
 'Lcn/kuwo/mod/mediaSession/KwMediaSessionService$d;-><init>(Lcn/kuwo/mod/mediaSession/KwMediaSessionService;)V',
 'Lw8/c;->q0(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILw8/d;)Lx8/a;',
 'Lw8/d;->d(Lm/c;)V', 'Lm/c;-><init>()V', 'Lm/c;->o()Z', 'Lm/c;->c()Ljava/lang/Object;', 'Lm/c;->b()I',
 'Lm/c;->h(I)V', 'Lm/c;->m(Ljava/lang/String;)V',
 'Lcn/kuwo/base/bean/KwList;->c()Ljava/util/List;',
 'Lb4/c0;-><init>(Ljava/lang/String;Ljava/util/List;)V', 'Lb4/c0;->a()Ljava/lang/String;', 'Lb4/c0;->b()Ljava/util/List;',
 'Lb4/x$c;->e()Ljava/lang/String;', 'Lb4/x$c;->d()Lcn/kuwo/base/bean/MusicList;',
 'Ld4/f;->f()Ljava/lang/String;',
 'Lh1/a;->k(Ljava/lang/String;Ljava/lang/String;IZ)Z', 'Lh1/a;->i(Ljava/lang/String;Ljava/lang/String;ZZ)Z',
 'Lv/b;->l(Ljava/lang/String;Ljava/lang/String;ZZ)Z', 'Lv/b;->q(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Z',
 'Landroid/support/v4/media/MediaMetadataCompat$b;-><init>()V',
 'Landroid/support/v4/media/MediaMetadataCompat$b;->e(Ljava/lang/String;Ljava/lang/String;)Landroid/support/v4/media/MediaMetadataCompat$b;',
 'Landroid/support/v4/media/MediaMetadataCompat$b;->c(Ljava/lang/String;J)Landroid/support/v4/media/MediaMetadataCompat$b;',
 'Landroid/support/v4/media/MediaMetadataCompat$b;->a()Landroid/support/v4/media/MediaMetadataCompat;',
 'Landroid/support/v4/media/MediaMetadataCompat;->d()Landroid/support/v4/media/MediaDescriptionCompat;',
 'Landroid/support/v4/media/MediaBrowserCompat$MediaItem;-><init>(Landroid/support/v4/media/MediaDescriptionCompat;I)V',
 'Landroid/support/v4/media/session/MediaSessionCompat;->p(I)V', 'Landroid/support/v4/media/session/MediaSessionCompat;->r(I)V',
]
for method in ['i','h','C','A','z']: required.append('Lcn/kuwo/mod/mediaSession/KwMediaSessionService$d;->'+method+'()V')
required += [
 'Ls5/b;->leanInitNow(Landroid/app/Application;)V',
 'Lv8/n0;->leanSuggestNow(Ljava/lang/String;)V',
 'Lv8/n0;->leanCancelSuggestion()V', 'Lv8/n0;->leanClearSuggestions()V',
 'Lcn/kuwo/kwmusiccar/ui/homerecommend/h;->leanRefreshNow(Z)V',
 'Lcn/kuwo/kwmusiccar/ui/homerecommend/h;->leanApplyHomeResponse(Lm/c;)Lse/h;',
 'Lcn/kuwo/kwmusiccar/ui/homerecommend/j;->G()V',
 'Lcn/kuwo/kwmusiccar/ui/homerecommend/j;->H()V',
 'Lv8/o;->n()Lu8/j0;',
 'Landroidx/fragment/app/Fragment;->getUserVisibleHint()Z',
 'Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;',
 'Landroidx/fragment/app/Fragment;->isResumed()Z',
 'Lcn/kuwo/base/imageloader/glide/KwRequestOptions;->p(II)Lcn/kuwo/base/imageloader/glide/KwRequestOptions;',
]
for cls in ['musiclib/MusicLibFragment','homerecommend/HomeRecommendFragment']:
    required.append('Lcn/kuwo/kwmusiccar/ui/'+cls+';->leanBindView(Landroid/view/View;Landroid/os/Bundle;)V')
api_symbols=json.loads((ROOT/'config/api-symbols.json').read_text())
required += api_symbols['methods']
for method in required: check('reflection symbol '+method, method in methods)
for field in api_symbols['fields']: check('API v2 field '+field,field in fields)
for field in [
 'Lcn/kuwo/kwmusiccar/ui/base/BaseMvpFragment;->D:Lv8/o;',
 'Lcn/kuwo/kwmusiccar/ui/homerecommend/HomeRecommendFragment;->F:Lcn/kuwo/kwmusiccar/ui/homerecommend/j;',
 'Lcn/kuwo/kwmusiccar/ui/nowplaying/NowPlayingFragment;->z:Lcn/kuwo/base/imageloader/glide/KwRequestOptions;',
 'Lcn/kuwo/kwmusiccar/ui/nowplaying/NowPlayingFragment;->H:Landroid/widget/ImageView;',
]: check('performance field '+field, field in fields)
for descriptor in ['f:J','g:Ljava/lang/String;','h:Ljava/lang/String;','j:Ljava/lang/String;','k:I','O0:Ljava/lang/String;']:
    check('music field '+descriptor, 'Lcn/kuwo/base/bean/Music;->'+descriptor in fields)
check('bridge provider defined', 'Lcn/kuwo/bridge/BridgeProvider;' in classes)
check('lean hooks defined', 'Lcn/kuwo/bridge/LeanHooks;' in classes)
check('GitHub updater defined','Lcn/kuwo/bridge/GithubUpdater;' in classes and 'Lcn/kuwo/bridge/UpdateActivity;' in classes)

# Check every new injected static call actually resolves in the final DEX files.
for path in (BUILD/'apktool').glob('smali*/**/*.smali'):
    for target in re.findall(r'invoke-static[^\n]*, (Lcn/kuwo/bridge/[^\n ]+)', path.read_text()):
        check('new static call '+target, target in methods)

A='{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(BUILD/'apktool/AndroidManifest.xml').getroot()
provider = [n for n in manifest.find('application').findall('provider') if n.get(A+'name') == 'cn.kuwo.bridge.BridgeProvider']
check('single exported bridge with no URI grants', len(provider)==1 and provider[0].get(A+'exported')=='true' and provider[0].get(A+'grantUriPermissions')=='false')
check('vehicle user-app package retained', manifest.get('package')==APPLICATION_ID)
boot=[n for n in manifest.find('application').findall('receiver') if n.get(A+'name')=='cn.kuwo.kwmusiccar.common.startup.BootReceiver']
check('boot receiver is internal and enabled',len(boot)==1 and boot[0].get(A+'exported')=='false' and boot[0].get(A+'enabled')=='true')
check('boot and package replacement actions', {'android.intent.action.BOOT_COMPLETED','android.intent.action.MY_PACKAGE_REPLACED'} <= {n.get(A+'name') for n in boot[0].findall('intent-filter/action')})
service=[n for n in manifest.find('application').findall('service') if n.get(A+'name')=='cn.kuwo.service.MainService']
check('original music service retains mediaPlayback foreground type',len(service)==1 and service[0].get(A+'foregroundServiceType')=='mediaPlayback')
check('music service survives task removal',service[0].get(A+'stopWithTask')=='false')
updater=[n for n in manifest.find('application').findall('activity') if n.get(A+'name')=='cn.kuwo.bridge.UpdateActivity']
check('update activity is private',len(updater)==1 and updater[0].get(A+'exported')=='false')
for cls in ['SilentStartup','Artwork','MusicApi','NetworkCatalog']:check('API v2 class '+cls,'Lcn/kuwo/bridge/'+cls+';' in classes)
check('original provider authorities moved out of system namespace', all(
    n.get(A+'authorities','').startswith(APPLICATION_ID+'.')
    for n in manifest.find('application').findall('provider') if n.get(A+'name')!='cn.kuwo.bridge.BridgeProvider'))
must_hide = {'fra_item_radio','fra_item_zhenxuan','iv_top_relax','iv_top_relax_deep','iv_mv','iv_download','iv_spectrum','ll_quality','ll_effect','img_karaoke','vip_store','layout_karaoke_small','ll_quality_effect'}
coverage = {name:0 for name in must_hide}
for p in (BUILD/'apktool/res').glob('layout*/*.xml'):
    for n in ET.parse(p).iter():
        name=n.get(A+'id','').split('/')[-1]
        if name in must_hide:
            check('hidden '+str(p.relative_to(BUILD/'apktool'))+':'+name, n.get(A+'visibility')=='gone' and n.get(A+'layout_height')=='0dp')
            coverage[name]+=1
check('every requested entry family has resource coverage', all(coverage.values()))
home_layouts = sorted((BUILD/'apktool/res').glob('layout*/fragment_main_top*.xml'))
compact_count = 0
public_ids = {n.get('name'): n.get('id') for n in ET.parse(BUILD/'apktool/res/values/public.xml').getroot() if n.get('type')=='id'}
for p in home_layouts:
    tree = ET.parse(p)
    nodes = {n.get(A+'id','').split('/')[-1]: n for n in tree.iter()}
    ids = ['fra_item_mine', 'fra_item_recomment', 'fra_item_music_lib']
    if not all(name in nodes for name in ids): continue
    compact_count += 1
    parents = {child: parent for parent in tree.iter() for child in parent}
    row = parents[nodes[ids[0]]]
    label = str(p.relative_to(BUILD/'apktool'))
    check('compact tab group '+label, row.get(A+'gravity')=='center' and row.get(A+'layout_width')=='wrap_content')
    check('no weighted tab spacers '+label, not any(c.tag=='Space' for c in row))
    for i, name in enumerate(ids):
        n = nodes[name]
        check('content width and fixed gap '+label+':'+name,
              parents[n] is row and n.get(A+'layout_width')=='wrap_content' and n.get(A+'layout_weight')=='0'
              and n.get(A+'layout_marginStart')==('0dp' if i==0 else '8dp')
              and n.get(A+'minWidth')=='80dp' and n.get(A+'paddingStart')=='12dp' and n.get(A+'paddingEnd')=='12dp')
        check('unclipped centered label '+label+':'+name,
              nodes[name.replace('fra_', 'tv_', 1)].get(A+'layout_width')=='wrap_content'
              and nodes[name.replace('fra_', 'tv_', 1)].get(A+'gravity')=='center')
    # Verify the resource actually packaged in the APK, in addition to source XML.
    packaged = subprocess.check_output([AAPT,'dump','xmltree',str(APK),'--file',label],text=True)
    (BUILD/('layout-'+p.parent.name+'-'+p.stem+'.txt')).write_text(packaged)
    check('compiled compact row '+label, 'E: Space' not in packaged and 'android:layout_weight' in packaged and 'android:layout_marginStart' in packaged)
    compiled_nodes = []
    for line in packaged.splitlines():
        if re.match(r'\s+E: ',line): compiled_nodes.append({})
        attribute = re.search(r'android:(\w+)\(0x[0-9a-f]+\)=(.*)',line)
        if attribute: compiled_nodes[-1][attribute[1]]=attribute[2]
    by_id = {n.get('id'): n for n in compiled_nodes if 'id' in n}
    for i, name in enumerate(ids):
        n = by_id.get('@'+public_ids[name], {})
        check('compiled tab dimensions '+label+':'+name,
              n.get('layout_width')=='-2' and n.get('layout_weight')=='0.000000e+00'
              and n.get('layout_marginStart')==('0.000000dp' if i==0 else '8.000000dp')
              and n.get('minWidth')=='80.000000dp')
check('landscape and both portrait resource variants checked', compact_count==3)
check('source APK unchanged', hashlib.sha256(Path(META['base_apk']).read_bytes()).hexdigest()==META['original_sha256'])
check('output hash matches build receipt', hashlib.sha256(APK.read_bytes()).hexdigest()==META['sha256'])
signature = (BUILD/'verify-signature.log').read_text()
check('same signing certificate as lean.1', 'f90366ddd1c20a75d21fb6162dc07572348922ab744e481c1ceb3d454d4593d0' in signature)

badging=subprocess.check_output([AAPT,'dump','badging',str(APK)],text=True)
(BUILD/'badging.txt').write_text(badging)
check('packaged versionCode/name', f"versionCode='{VERSION_CODE}'" in badging and f"versionName='{VERSION_NAME}'" in badging and f"name='{APPLICATION_ID}'" in badging)
xmltree=subprocess.check_output([AAPT,'dump','xmltree',str(APK),'--file','AndroidManifest.xml'],text=True)
(BUILD/'manifest-tree.txt').write_text(xmltree)
check('packaged bridge component', 'cn.kuwo.bridge.BridgeProvider' in xmltree and 'cn.kuwo.kwmusiccar.bridge' in xmltree)
report={'status':'PASS','checks':checks,'check_count':len(checks),'reflection_symbols':len(required),
 'resource_coverage':coverage,'apk':str(APK),'sha256':META['sha256'],
 'runtime_tested':False,'limitations':['No Android runtime or car UI execution','No network/search-service validation','No performance measurement']}
(BUILD/'artifact-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:report[k] for k in ['status','check_count','reflection_symbols','runtime_tested']},indent=2))
