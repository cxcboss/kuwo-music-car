#!/usr/bin/env python3
"""Decode the pinned base APK, apply maintained patches, compile and sign outside the repo."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from version import CONFIG, VERSION_NAME, VERSION_CODE, APPLICATION_ID

ROOT=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--base-apk',type=Path,default=os.environ.get('KUWO_BASE_APK'))
parser.add_argument('--keys',type=Path,default=Path.home()/'.local/share/kuwo-music/signing')
parser.add_argument('--output',type=Path,default=Path.home()/'.cache/kuwo-music/builds')
args=parser.parse_args()
if args.base_apk is None: parser.error('Supply --base-apk or KUWO_BASE_APK')
base=Path(args.base_apk).expanduser().resolve()
sha256=hashlib.sha256(base.read_bytes()).hexdigest()
if sha256!=CONFIG['baseSha256']: raise SystemExit('Wrong base APK SHA-256')
keys=args.keys.expanduser().resolve()
if not (keys/'kuwo-lean.jks').is_file() or not (keys/'password.txt').is_file():
    raise SystemExit('Original signing key and password.txt are required; see docs/构建与发布.md')
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
tools=sdk/'build-tools/36.1.0'
android=sdk/'platforms/android-35/android.jar'
apktool=shutil.which('apktool')
if not apktool or not android.is_file() or not (tools/'d8').is_file(): raise SystemExit('Install the documented Android build tools first')
out=args.output.expanduser().resolve()/datetime.now().astimezone().strftime('%Y%m%d-%H%M%S')
out.mkdir(parents=True,exist_ok=False)

def run(command,name,env=None):
    with (out/(name+'.log')).open('w') as log:
        result=subprocess.run([str(a) for a in command],stdout=log,stderr=subprocess.STDOUT,env=env)
    if result.returncode:
        print((out/(name+'.log')).read_text()[-8000:])
        raise SystemExit(f'{name} failed; build: {out}')
    print(name+': OK',flush=True)

run([apktool,'d','-p',out/'framework',base,'-o',out/'base'],'decode')
run([os.sys.executable,ROOT/'scripts/patch_apk.py',out/'apktool',out/'base'],'patch')
generated=out/'generated/cn/kuwo/bridge/BuildInfo.java'
generated.parent.mkdir(parents=True)
generated.write_text('package cn.kuwo.bridge;\npublic final class BuildInfo {\n'
    +f' public static final String VERSION_NAME="{VERSION_NAME}";\n'
    +f' public static final int VERSION_CODE={VERSION_CODE};\n'
    +f' public static final String APPLICATION_ID="{APPLICATION_ID}";\n'
    +f' public static final String REPOSITORY="{CONFIG["repository"]}";\n'
    +' private BuildInfo() {}\n}\n')
run(['javac','--release','8','-cp',android,'-d',out/'classes',*sorted((ROOT/'src').rglob('*.java')),generated],'javac')
run(['javac','--release','8','-cp',android,'-d',out/'sdk-classes',ROOT/'sdk/KuwoBridgeClient.java'],'sdk-javac')
with zipfile.ZipFile(out/'bridge-classes.jar','w') as jar:
    for p in sorted((out/'classes').rglob('*.class')): jar.write(p,p.relative_to(out/'classes'))
(out/'dex').mkdir()
run([tools/'d8','--release','--min-api','21','--lib',android,'--output',out/'dex',out/'bridge-classes.jar'],'d8')
run([apktool,'b','-p',out/'framework',out/'apktool','-o',out/'rebuilt.apk'],'apktool-build')
with zipfile.ZipFile(out/'rebuilt.apk') as source,zipfile.ZipFile(out/'injected.apk','w') as target:
    assert 'classes4.dex' not in source.namelist()
    for entry in source.infolist():
        if not entry.filename.upper().startswith('META-INF/'): target.writestr(entry,source.read(entry.filename))
    target.write(out/'dex/classes.dex','classes4.dex',compress_type=zipfile.ZIP_DEFLATED)
run([tools/'zipalign','-P','16','-f','4',out/'injected.apk',out/'aligned.apk'],'align')
final=out/f'{VERSION_NAME}-酷我音乐.apk'
sign_env=dict(os.environ)
sign_env['KUWO_SIGN_PASSWORD']=(keys/'password.txt').read_text().strip()
run([tools/'apksigner','sign','--ks',keys/'kuwo-lean.jks','--ks-key-alias','kuwo-lean',
    '--ks-pass','env:KUWO_SIGN_PASSWORD','--key-pass','env:KUWO_SIGN_PASSWORD','--min-sdk-version','21',
    '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--out',final,out/'aligned.apk'],'sign',sign_env)
run([tools/'apksigner','verify','--verbose','--print-certs',final],'verify-signature')
run([tools/'zipalign','-c','-P','16','4',final],'verify-alignment')
digest=hashlib.sha256(final.read_bytes()).hexdigest()
result={'apk':str(final),'application_id':APPLICATION_ID,'version_name':VERSION_NAME,'version_code':VERSION_CODE,
    'sha256':digest,'bytes':final.stat().st_size,'base_apk':str(base),'original_sha256':sha256,
    'built_at':datetime.now().astimezone().isoformat(timespec='seconds')}
(out/'build-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
update={'schemaVersion':1,'applicationId':APPLICATION_ID,'versionName':VERSION_NAME,'versionCode':VERSION_CODE,
    'fileName':final.name,'size':final.stat().st_size,'sha256':digest,
    'downloadUrl':f'https://github.com/{CONFIG["repository"]}/releases/download/v{VERSION_NAME}/{VERSION_NAME}-kuwo-music.apk'}
(out/'update.json').write_text(json.dumps(update,ensure_ascii=False,indent=2)+'\n')
(out/'SHA256SUMS').write_text(digest+'  '+VERSION_NAME+'-kuwo-music.apk\n')
run([os.sys.executable,ROOT/'scripts/verify_build.py',out],'verify-artifact')
print(json.dumps(result,ensure_ascii=False,indent=2))
