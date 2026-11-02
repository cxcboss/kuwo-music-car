#!/usr/bin/env python3
"""Advance one hundredth and increase Android's monotonic package version code."""
import json
from pathlib import Path

path=Path(__file__).resolve().parent.parent/'config/version.json'
config=json.loads(path.read_text())
config['revision']+=1
config['versionName']=f"1.{config['revision']//100}.{config['revision']%100:02d}"
config['versionCode']+=1
path.write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n')
contract_path=path.parent.parent/'sdk/api-contract.json'
contract=json.loads(contract_path.read_text())
contract['buildVersion']=config['versionName']
contract['versionCode']=config['versionCode']
contract_path.write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n')
print(config['versionName'],config['versionCode'])
