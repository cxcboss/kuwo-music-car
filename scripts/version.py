import json
from pathlib import Path

CONFIG = json.loads((Path(__file__).resolve().parent.parent / 'config/version.json').read_text())
VERSION_NAME = CONFIG['versionName']
VERSION_CODE = CONFIG['versionCode']
APPLICATION_ID = CONFIG['applicationId']
