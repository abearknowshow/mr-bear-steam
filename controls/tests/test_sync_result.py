import sys, unittest, json, tempfile, threading, http.client
from pathlib import Path
from http.server import ThreadingHTTPServer
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from setup_server import sync_result, make_handler, PairStore

class SyncResultTest(unittest.TestCase):
    def test_bounded_observer_metrics(self):
        body=dict(build='0.10',stage='Live subscription accepted; waiting for reported lights',reportedOn=None,fresh=False,
                  messages=3,reported=2,lights=1,accepted=0,kind='light-fields',view='original-watch',attempt=0,command='idle')
        self.assertEqual(sync_result(body)['messages'],3)
        self.assertEqual(sync_result(dict(body,build='0.12',view='bear-overlay'))['view'],'bear-overlay')
        self.assertEqual(sync_result(dict(body,build='0.13',view='bear-overlay'))['build'],'0.13')
        self.assertEqual(sync_result(dict(body,build='0.14',view='bear-overlay',stage='Status refresh request failed'))['build'],'0.14')
        self.assertEqual(sync_result(dict(body,build='0.15',view='bear-overlay',stage='Status refresh request failed'))['build'],'0.15')
        self.assertEqual(sync_result(dict(body,build='0.16',view='bear-overlay',command='unchanged'))['command'],'unchanged')
        self.assertEqual(sync_result(dict(body,build='0.17',view='bear-overlay'))['build'],'0.17')
        self.assertEqual(sync_result(dict(body,build='0.18',view='bear-overlay'))['build'],'0.18')
        self.assertEqual(sync_result(dict(body,build='0.19',view='bear-overlay'))['build'],'0.19')
        self.assertEqual(sync_result(dict(body,build='0.20',view='bear-overlay'))['build'],'0.20')
        self.assertEqual(sync_result(dict(body,build='0.21',view='bear-overlay'))['build'],'0.21')
        self.assertEqual(sync_result(dict(body,build='0.22',view='bear-overlay'))['build'],'0.22')
        self.assertEqual(sync_result(dict(body,build='0.11',view='original-visible'))['view'],'original-visible')
        for overrides in ({'messages':-1},{'reported':True},{'lights':1000001},{'accepted':1.5},
                          {'kind':'raw secret'},{'view':'other'},{'command':'token'},{'attempt':-1}):
            with self.assertRaises(ValueError):sync_result(dict(body,**overrides))
        with self.assertRaises(ValueError):sync_result(dict(body,rawShadow='rejected'))

    def test_minimal_result(self):
        result=sync_result(dict(build='0.9', stage='Checking GET subscription failed', reportedOn=None, fresh=False))
        self.assertIsNone(result['reportedOn'])
        self.assertIn('receivedAt', result)

    def test_no_secret_or_raw_log_fields(self):
        base=dict(build='0.9', stage='Live light report received', reportedOn=True, fresh=True)
        for extra in ('token','email','password','rawShadow','logs'):
            with self.assertRaises(ValueError): sync_result(dict(base, **{extra:'rejected'}))
        for overrides in ({'stage':'arbitrary logs'}, {'reportedOn':1}, {'fresh':'true'}, {'build':'other'}):
            with self.assertRaises(ValueError): sync_result(dict(base, **overrides))

    def test_bounded_upload_on_isolated_loopback_server(self):
        with tempfile.TemporaryDirectory() as folder, patch('setup_server.ROOT', Path(folder)):
            server=ThreadingHTTPServer(('127.0.0.1',0),make_handler('relay',PairStore(),'test-only'))
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            def post(body,origin=None):
                connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=2)
                headers={'Content-Type':'application/json'}
                if origin:headers['Origin']=origin
                connection.request('POST','/sync-result',json.dumps(body),headers)
                response=connection.getresponse();status=response.status;response.read();connection.close();return status
            try:
                body=dict(build='0.9',stage='Checking GET subscription failed (MQTT 128)',reportedOn=None,fresh=False)
                self.assertEqual(post(body,'http://example.invalid'),403)
                self.assertEqual(post(dict(body,password='TEST-ONLY')),400)
                self.assertFalse((Path(folder)/'build/sync-result.json').exists())
                self.assertEqual(post(body),200)
                result=json.loads((Path(folder)/'build/sync-result.json').read_text())
                self.assertEqual(result['stage'],body['stage'])
                self.assertEqual(set(result),set(body)|{'receivedAt'})
            finally:
                server.shutdown();server.server_close();thread.join(timeout=2)
