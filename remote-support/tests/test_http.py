import os,pathlib;os.environ.setdefault('BEAR_HOUSEHOLD',str(pathlib.Path(__file__).resolve().parents[2]/'household.example.json'))  # tests use made-up values
import unittest,threading,urllib.request,urllib.error,json
from http.server import ThreadingHTTPServer
from relay import handler,SupportStore
class HTTPTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.store=SupportStore();cls.server=ThreadingHTTPServer(('127.0.0.1',0),handler('control',cls.store,'test-only'));cls.port=cls.server.server_address[1];threading.Thread(target=cls.server.serve_forever,daemon=True).start()
 @classmethod
 def tearDownClass(cls):cls.server.shutdown();cls.server.server_close()
 def request(self,path,body=None,auth='Bearer test-only',origin=None,host=None):
  h={'Authorization':auth,'Content-Type':'application/json'}
  if origin:h['Origin']=origin
  if host:h['Host']=host
  r=urllib.request.Request(f'http://127.0.0.1:{self.port}'+path,data=None if body is None else json.dumps(body).encode(),headers=h)
  try:
   with urllib.request.urlopen(r) as x:return x.status,json.load(x)
  except urllib.error.HTTPError as e:
   try:return e.code,json.load(e)
   finally:e.close()
 def test_auth_origin_and_host(self):
  self.assertEqual(self.request('/session',auth='')[0],403)
  self.assertEqual(self.request('/session',origin='https://example.com')[0],403)
  self.assertEqual(self.request('/session',host='evil.example')[0],403)
  self.assertEqual(self.request('/session')[0],200)
 def test_cannot_execute_equipment_or_shell(self):
  self.assertEqual(self.request('/action',{'op':'shell'})[0],409)
  self.assertEqual(self.request('/steam',{'on':True})[0],404)
 def test_approve_stop(self):
  p=self.store.begin();self.assertEqual(self.request('/approve',{'code':p['code']})[0],200)
  self.assertTrue(self.request('/session')[1]['approved']);self.assertEqual(self.request('/stop',{})[0],200)
  self.assertEqual(self.request('/session')[1],{'state':'ended'})
if __name__=='__main__':unittest.main()
