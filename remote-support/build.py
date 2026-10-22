from pathlib import Path
import os,subprocess,shutil,zipfile,json,hashlib,re
import sys;ROOT=Path(__file__).resolve().parent;OUT=ROOT/'build';sys.path.insert(0,str(ROOT.parent/'controls'))
import household
from toolchain import JDK,TOOLS,ANDROID
ENV=dict(os.environ,JAVA_HOME=str(JDK));ENV['PATH']=str(JDK/'bin')+':'+ENV['PATH']
def run(*args):subprocess.run(list(map(str,args)),env=ENV,check=True)
def build(out,manifest,sources,keystore,password,alias,assets=ROOT/'assets'):
 out.mkdir(parents=True,exist_ok=True)
 for d in ('classes','dex'):
  p=out/d
  if p.exists():shutil.rmtree(p)
  p.mkdir()
 (out/'AndroidManifest.xml').write_text(manifest)
 run(TOOLS/'aapt','package','-f','-M',out/'AndroidManifest.xml','-A',assets,'-S',ROOT/'res','-I',ANDROID,'-F',out/'resources.apk')
 run(JDK/'bin/javac','--release','8','-Xlint:-options','-cp',ANDROID,'-d',out/'classes',*sources)
 run(TOOLS/'d8','--min-api','29','--lib',ANDROID,'--output',out/'dex',*sorted((out/'classes').rglob('*.class')))
 shutil.copyfile(out/'resources.apk',out/'unsigned.apk')
 with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
  for p in (out/'dex').glob('*.dex'):z.write(p,p.name)
 run(TOOLS/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk')
 f=out/'password';f.write_text(password+'\n'+password+'\n');f.chmod(0o600)
 try:run(TOOLS/'apksigner','sign','--ks',keystore,'--ks-key-alias',alias,'--ks-pass','file:'+str(f),'--key-pass','file:'+str(f),'--out',out/'signed.apk',out/'aligned.apk')
 finally:f.unlink()
 run(TOOLS/'apksigner','verify','--verbose',out/'signed.apk')
 return out/'signed.apk'
if __name__=='__main__':
 os.umask(0o077)
 if not os.environ.get('STEAM_SIGNING_KEYSTORE') or not os.environ.get('STEAM_SIGNING_PASSWORD_FILE'):raise SystemExit('Set STEAM_SIGNING_KEYSTORE and STEAM_SIGNING_PASSWORD_FILE; see docs/SETUP.md.')
 if not (ROOT/'assets/setup-server.crt').exists():raise SystemExit('assets/setup-server.crt is missing: run python3 controls/make_certificate.py first.')
 own=household.java(household.load(),'local.steam.support',OUT/'generated')
 apk=build(OUT,(ROOT/'AndroidManifest.xml').read_text(),[own]+sorted((ROOT/'src').rglob('*.java')),Path(os.environ['STEAM_SIGNING_KEYSTORE']),Path(os.environ['STEAM_SIGNING_PASSWORD_FILE']).read_text().strip(),os.environ.get('STEAM_SIGNING_ALIAS','bear-steam'))
 # Named by versionName so earlier APKs (0.1 is installed and served) are never overwritten.
 version=re.search(r'android:versionName="([0-9.]+)"',(ROOT/'AndroidManifest.xml').read_text()).group(1)
 target=ROOT/f'bear-remote-support-{version}.apk';shutil.copyfile(apk,target);sums=ROOT/'SHA256.txt'
 lines=[l for l in (sums.read_text().splitlines() if sums.exists() else []) if not l.endswith('  '+target.name)]
 sums.write_text('\n'.join(lines+[hashlib.sha256(target.read_bytes()).hexdigest()+'  '+target.name])+'\n')
