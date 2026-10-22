"""Offline unit + loopback fake-device tests. Never targets the shower."""
from pathlib import Path
import subprocess
import household
from toolchain import JDK as JAVA_HOME

ROOT=Path(__file__).resolve().parent
JDK=JAVA_HOME/'bin'
out=ROOT/'build/test-classes';out.mkdir(parents=True,exist_ok=True)
import os
# Every suite, including the Mac servers under test, sees only the made-up example household.
ENV=dict(os.environ,BEAR_HOUSEHOLD=str(household.EXAMPLE))
def run(*args):subprocess.run([str(x) for x in args],cwd=ROOT,check=True,env=ENV)
gson=ROOT/'dependencies/gson-2.8.9.jar'
sources=[ROOT/'src/local/steam/controls'/f'{name}.java' for name in ('Protocol','ControlEngine','UdpTransport','BridgeLocator','BridgeTransport','LightPayload','LightReport','LightFeed','ShadowRead','ReadWindow','ReadMetrics','StatusRequest','ReaderRetry','Timeline','StatusGrace','PanelPolicy')]
# Tests always run against the made-up household.example.json, never your own values.
example=household.java(household.load(example=True),'local.steam.controls',ROOT/'build/test-generated')
run(JDK/'javac','--release','8','-Xlint:-options','-cp',gson,'-d',out,example,*sources,*sorted((ROOT/'tests/local/steam/controls').glob('*.java')))
for name in ('ReaderRetryTest','StatusRefreshTest','BridgeTransportTest','ProtocolTest','EngineTest','LightPayloadTest','LightReportTest','ShadowReadTest','ReadWindowTest','ReadMetricsTest','TimelineTest','StatusGraceTest','PanelPolicyTest'):
    run(JDK/'java','-cp',str(out)+':'+str(gson),'local.steam.controls.'+name,ROOT/'tests/status-fixture.bin')
run('python3',ROOT/'tests/reader_cleanup_test.py')
run('python3',ROOT/'tests/network_test.py')
run('python3',ROOT/'tests/delivery_test.py')
run('python3',ROOT/'tests/transport_test.py')
run('python3','-m','unittest','discover','-s',ROOT/'tests','-p','test_setup.py')
run('node','--check',ROOT/'assets/live.js')
run('node','--check',ROOT/'setup.js')
run('node',ROOT/'tests/ui_test.cjs')
run('node',ROOT/'tests/startup_test.cjs')
run('node',ROOT/'tests/light_sync_test.cjs')
run('node',ROOT/'tests/timeline_ui_test.cjs')
run('node',ROOT/'tests/panel_ui_test.cjs')
run('python3','-m','unittest','discover','-s',ROOT/'tests','-p','test_sync_result.py')
run('python3','-m','unittest','discover','-v','-s',ROOT/'tests','-p','test_timeline.py')
print('All deterministic and loopback checks passed. No real equipment commands were sent.')
