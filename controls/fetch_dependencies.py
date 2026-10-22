"""Restore only pinned, public Maven Central artifacts; verify each SHA-256."""
from pathlib import Path
import hashlib,json,urllib.request

ROOT=Path(__file__).resolve().parent/'dependencies'
for item in json.loads((ROOT/'lock.json').read_text()):
    if not item['url'].startswith('https://repo.maven.apache.org/maven2/'):
        raise ValueError('Unexpected artifact host')
    target=ROOT/item['name']
    data=target.read_bytes() if target.exists() else urllib.request.urlopen(item['url'],timeout=30).read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']:
        raise ValueError('Digest mismatch: '+item['name'])
    target.write_bytes(data)
    print('Verified',item['name'])
