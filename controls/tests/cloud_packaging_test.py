"""Offline runtime smoke test using Paho classes + resources from the actual APK.

Class bytecode comes from the pinned dependency because host Java cannot run
DEX. Resource lookup comes exclusively from the packaged APK, so missing
ServiceLoader registration is exercised, not hidden by the original JAR.
"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
version = ET.parse(ROOT / "AndroidManifest.xml").getroot().get(ANDROID + "versionName")
apk = Path(sys.argv[1]) if len(sys.argv) == 2 else ROOT / f"bear-steam-{version}.apk"
out = ROOT / "build/cloud-packaging-test"
out.mkdir(parents=True, exist_ok=True)
runtime = out / "paho-with-packaged-resources.jar"
service = "META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory"
with ZipFile(ROOT / "dependencies/org.eclipse.paho.client.mqttv3-1.2.5.jar") as dependency, ZipFile(apk) as package:
    expected = dependency.read(service)
    with ZipFile(runtime, "w", ZIP_DEFLATED) as target:
        for name in dependency.namelist():
            if name.endswith(".class"):
                target.writestr(name, dependency.read(name))
            elif not name.endswith("/") and name in package.namelist():
                target.writestr(name, package.read(name))
    packaged_services = package.read(service) if service in package.namelist() else None

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import toolchain
jdk = toolchain.JDK / "bin"
subprocess.run([str(jdk / "javac"), "--release", "8", "-Xlint:-options", "-cp", str(runtime), "-d", str(out),
                str(ROOT / "tests/runtime/MqttModuleSmoke.java")], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out) + ":" + str(runtime), "MqttModuleSmoke"], check=True)
assert packaged_services == expected, "MQTT factory registration differs from the pinned dependency"
print("Built APK retains the exact MQTT service registration.")
