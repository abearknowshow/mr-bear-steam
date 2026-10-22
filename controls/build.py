"""Build the Mr. Bear Steam APK with the local SDK. No runtime contact with the shower."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,zipfile
import household
from toolchain import JDK,TOOLS,ANDROID
ROOT=Path(__file__).resolve().parent
VERSION=re.search(r'android:versionName="([0-9.]+)"',(ROOT/'AndroidManifest.xml').read_text()).group(1)
APK=ROOT/f'bear-steam-{VERSION}.apk'
def main():
 os.umask(0o077)
 out=ROOT/'build';out.mkdir(exist_ok=True);classes=out/'classes';dex=out/'dex'
 generated_src=out/'generated'
 for generated in (classes,dex,out/'jni',generated_src):
  if generated.exists():shutil.rmtree(generated)
  generated.mkdir()
 env=dict(os.environ,JAVA_HOME=str(JDK));env['PATH']=str(JDK/'bin')+os.pathsep+env['PATH']
 # Your household.json becomes Household.java; it never enters the repository.
 own=household.java(household.load(),'local.steam.controls',generated_src)
 if not (ROOT/'assets/setup-server.crt').exists():raise SystemExit('assets/setup-server.crt is missing: run python3 controls/make_certificate.py first (docs/SETUP.md).')
 def run(*args):subprocess.run([str(a) for a in args],cwd=ROOT,env=env,check=True)
 # Only pinned dependency artifacts/extracted classes. Never include source ZIPs or private config.
 records=json.loads((ROOT/'dependencies/lock.json').read_text())
 for record in records:
  data=(ROOT/'dependencies'/record['name']).read_bytes()
  if hashlib.sha256(data).hexdigest()!=record['sha256']:raise ValueError('Dependency digest mismatch')
 # Re-extract pinned AAR classes/native files on each build instead of trusting stale outputs.
 jars=[]
 for record in records:
  artifact=ROOT/'dependencies'/record['name']
  if artifact.suffix=='.jar':jars.append(artifact)
  elif artifact.suffix=='.aar':
   with zipfile.ZipFile(artifact) as aar:
    target=out/(artifact.stem+'.jar');target.write_bytes(aar.read('classes.jar'));jars.append(target)
    for name in aar.namelist():
     if name.startswith('jni/') and name.endswith('.so'):
      native=out/name;native.parent.mkdir(parents=True,exist_ok=True);native.write_bytes(aar.read(name))
 run(TOOLS/'aapt','package','-f','-M',ROOT/'AndroidManifest.xml','-A',ROOT/'assets','-S',ROOT/'res','-I',ANDROID,'-F',out/'resources.apk')
 run(JDK/'bin/javac','--release','8','-Xlint:-options','-cp',os.pathsep.join(map(str,[ANDROID,*jars])),'-d',classes,own,*sorted((ROOT/'src').rglob('*.java')))
 run(TOOLS/'d8','--min-api','29','--lib',ANDROID,'--output',dex,*sorted(classes.rglob('*.class')),*jars)
 unsigned=out/'unsigned.apk';unsigned.write_bytes((out/'resources.apk').read_bytes())
 with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
  for f in dex.glob('*.dex'):z.write(f,f.name)
  for f in (out/'jni').rglob('*.so'):z.write(f,'lib/'+str(f.relative_to(out/'jni')))
  # ServiceLoader descriptors are runtime resources, not signature metadata.
  # Paho cannot construct even a WSS client when its factory registration is stripped.
  seen=set(z.namelist())
  for jar in jars:
   with zipfile.ZipFile(jar) as dependency:
    for name in dependency.namelist():
     if name.endswith('/') or name.endswith('.class') or (name.startswith('META-INF/') and not name.startswith('META-INF/services/')) or name in seen:continue
     z.writestr(name,dependency.read(name));seen.add(name)
 run(TOOLS/'zipalign','-f','4',unsigned,out/'aligned.apk')
 # Signing key: your own, from the environment. Updates must keep using the same key.
 if not os.environ.get('STEAM_SIGNING_KEYSTORE') or not os.environ.get('STEAM_SIGNING_PASSWORD_FILE'):
  raise SystemExit('Set STEAM_SIGNING_KEYSTORE and STEAM_SIGNING_PASSWORD_FILE (and optionally STEAM_SIGNING_ALIAS); see docs/SETUP.md.')
 keystore=Path(os.environ['STEAM_SIGNING_KEYSTORE']);signing_password=Path(os.environ['STEAM_SIGNING_PASSWORD_FILE']).read_text().strip()
 alias=os.environ.get('STEAM_SIGNING_ALIAS','bear-steam')
 password=out/'signing-password.txt';password.write_text((signing_password+'\n')*2);password.chmod(0o600)
 try:
  run(TOOLS/'apksigner','sign','--ks',keystore,'--ks-key-alias',alias,'--ks-pass','file:'+str(password),'--key-pass','file:'+str(password),'--out',APK,out/'aligned.apk')
 finally:password.unlink(missing_ok=True)
 run(TOOLS/'apksigner','verify','--verbose',APK)
 run('python3',ROOT/'tests/cloud_packaging_test.py',APK)
 run(TOOLS/'aapt','dump','badging',APK)
 digest=hashlib.sha256((APK).read_bytes()).hexdigest();(ROOT/'SHA256.txt').write_text(digest+'  '+APK.name+'\n');print('APK SHA-256:',digest)
if __name__=='__main__':main()
