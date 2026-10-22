"""Run production AwsShadowRead against a fake SDK boundary, not a copied reader implementation."""
from pathlib import Path
from zipfile import ZipFile
import os, subprocess, sys
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root))
import household, toolchain
out=root/'build/reader-callback-test';out.mkdir(exist_ok=True)
jdk=toolchain.JDK/'bin'
jars=[]
for name in ('core','iot'):
    target=out/f'aws-{name}.jar'
    with ZipFile(root/f'dependencies/aws-android-sdk-{name}-2.81.1.aar') as archive:
        target.write_bytes(archive.read('classes.jar'))
    jars.append(target)
jars += [root/'dependencies/org.eclipse.paho.client.mqttv3-1.2.5.jar',root/'dependencies/gson-2.8.9.jar']
classpath=os.pathsep.join(map(str,jars))
sources=[root/f'src/local/steam/controls/{name}.java' for name in ('AwsShadowRead','ShadowRead','LightFeed','LightReport','LightPayload','ControlEngine','Protocol','ReadMetrics','StatusRequest')]
sources+=list((root/'tests/runtime/subscription').rglob('*.java'))
sources.append(household.java(household.load(example=True),'local.steam.controls',out/'generated'))
subprocess.run([str(jdk/'javac'),'--release','8','-cp',classpath,'-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(jdk/'java'),'-cp',str(out)+os.pathsep+classpath,'ReaderCleanupTest'],check=True,timeout=15)
