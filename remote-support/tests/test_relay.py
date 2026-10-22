import os,pathlib;os.environ.setdefault('BEAR_HOUSEHOLD',str(pathlib.Path(__file__).resolve().parents[2]/'household.example.json'))  # tests use made-up values
import unittest
from relay import SupportStore, validate_action
class Tests(unittest.TestCase):
 def setUp(self):
  self.now=0.;self.s=SupportStore(lambda:self.now);self.p=self.s.begin();self.auth={k:self.p[k] for k in ('id','key')}
 def exchange(self,screen=None,result=None):return self.s.exchange(dict(self.auth,screen=screen,result=result))
 def screen(self):return {'hash':'a'*64,'windows':[],'nodes':[]}
 def test_no_data_before_approval(self):
  self.assertFalse(self.exchange()['approved'])
  with self.assertRaises(ValueError):self.exchange(self.screen())
 def test_panel_end_clears_screen(self):
  self.s.approve(self.p['code']);self.exchange(self.screen());self.s.end(self.auth);self.assertEqual(self.s.status(),{'state':'ended'})
 def test_wrong_key(self):
  with self.assertRaises(ValueError):self.s.exchange(dict(self.auth,key='wrong',screen=None,result=None))
 def test_expiry_and_stop(self):
  # 0.2: two-hour cap (was 30 minutes).
  self.s.approve(self.p['code']);self.now=7199;self.exchange();self.now=7201
  with self.assertRaises(ValueError):self.exchange()
 def test_one_delivery_no_retry(self):
  self.s.approve(self.p['code']);self.exchange(self.screen());a=self.s.queue({'op':'back','hash':'a'*64});cmd=self.exchange(self.screen())['command'];self.assertEqual(cmd['id'],a['id']);self.assertIsNone(self.exchange(self.screen())['command'])
  with self.assertRaises(ValueError):self.s.queue({'op':'back','hash':'a'*64})
  self.exchange(self.screen(),{'id':a['id'],'status':'done'});self.s.queue({'op':'back','hash':'a'*64})
 def test_old_screen_and_expired_command(self):
  self.s.approve(self.p['code']);self.exchange(self.screen());self.now=5
  with self.assertRaises(ValueError):self.s.queue({'op':'back','hash':'a'*64})
  self.exchange(self.screen());self.s.queue({'op':'back','hash':'a'*64});self.now=12
  self.assertIsNone(self.exchange(self.screen())['command'])
 def test_unsupported_url_and_action(self):
  for a in ({'op':'shell','hash':'a'*64},{'op':'url','url':'https://evil.example','hash':'a'*64},{'op':'text','node':'1','text':'x'*300,'hash':'a'*64}):
   with self.assertRaises(ValueError):validate_action(a)
 def test_no_queue_before_pairing_or_snapshot(self):
  with self.assertRaises(ValueError):self.s.queue({'op':'back','hash':'a'*64})
  self.s.approve(self.p['code'])
  with self.assertRaises(ValueError):self.s.queue({'op':'back','hash':'a'*64})
if __name__=='__main__':unittest.main()
