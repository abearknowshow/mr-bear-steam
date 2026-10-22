"""Android SDK and JDK locations, from the environment with macOS/Homebrew defaults."""
from pathlib import Path
import os

SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
JDK = Path(os.environ.get('JAVA_HOME') or '/opt/homebrew/opt/openjdk@21')
TOOLS = SDK / 'build-tools' / os.environ.get('ANDROID_BUILD_TOOLS', '36.0.0')
ANDROID = SDK / 'platforms' / os.environ.get('ANDROID_PLATFORM', 'android-36') / 'android.jar'
ADB = SDK / 'platform-tools/adb'
# The offline emulator tests only ever talk to this one emulator serial.
EMULATOR = os.environ.get('BEAR_TEST_EMULATOR', 'emulator-5580')


def env():
    e = dict(os.environ, JAVA_HOME=str(JDK))
    e['PATH'] = str(JDK / 'bin') + os.pathsep + e['PATH']
    return e
