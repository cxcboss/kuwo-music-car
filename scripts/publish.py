#!/usr/bin/env python3
"""Publish the signed APK and its generated update manifest to a formal GitHub Release."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from version import CONFIG, VERSION_NAME, VERSION_CODE

root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--build',type=Path,required=True)
args=parser.parse_args()
build=args.build.expanduser().resolve()
result=json.loads((build/'build-result.json').read_text())
verification=json.loads((build/'artifact-verification.json').read_text())
apk=Path(result['apk'])
if result['version_name']!=VERSION_NAME or result['version_code']!=VERSION_CODE:
    raise SystemExit('Build version does not match config/version.json')
if verification['status']!='PASS' or hashlib.sha256(apk.read_bytes()).hexdigest()!=result['sha256']:
    raise SystemExit('APK verification failed')
if subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip():
    raise SystemExit('Commit project changes before publishing')
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
subprocess.run(['gh','release','create','v'+VERSION_NAME,'--repo',CONFIG['repository'],
    '--target',commit,'--draft','--title',VERSION_NAME,'--notes-file',str(root/'docs/版本更新.md'),
    str(apk),str(build/'update.json'),str(build/'SHA256SUMS')],cwd=root,check=True)
subprocess.run(['gh','release','edit','v'+VERSION_NAME,'--repo',CONFIG['repository'],
    '--draft=false','--latest'],cwd=root,check=True)
