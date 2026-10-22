import importlib.util
from pathlib import Path
import unittest

path=Path(__file__).resolve().parents[1]/'setup_server.py'
spec=importlib.util.spec_from_file_location('setup_server',path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.now=0
        self.store=module.PairStore(lambda:self.now)
        self.pair=self.store.begin('panel')

    def test_one_use_and_no_password_in_result(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        self.assertEqual(self.store.poll(self.pair['id'],'panel')['password'],'TEST ONLY')
        self.assertEqual(self.store.poll(self.pair['id'],'panel'),{'state':'delivered'})
        self.assertNotIn('credentials',self.store.items[self.pair['id']])
        self.store.result(self.pair['id'],'panel','connected')
        self.assertEqual(self.store.state(self.pair['code']),'connected')

    def test_wrong_requesting_device_cannot_read(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        with self.assertRaises(ValueError):self.store.poll(self.pair['id'],'other')
        with self.assertRaises(ValueError):self.store.result(self.pair['id'],'other','connected')

    def test_credentials_expire_in_two_minutes(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        self.now=121
        with self.assertRaises(ValueError):self.store.poll(self.pair['id'],'panel')
        self.assertEqual(self.store.items,{})

    def test_code_expires_in_five_minutes(self):
        self.now=301
        with self.assertRaises(ValueError):self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        self.assertEqual(self.store.items,{})

    def test_no_replacing_or_replaying_credentials(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        with self.assertRaises(ValueError):self.store.submit(self.pair['code'],'other@example.invalid','OTHER')
        self.store.poll(self.pair['id'],'panel')
        with self.assertRaises(ValueError):self.store.submit(self.pair['code'],'other@example.invalid','OTHER')

    def test_cancel_discards_credentials(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        self.store.result(self.pair['id'],'panel','canceled')
        self.assertEqual(self.store.poll(self.pair['id'],'panel'),{'state':'canceled'})
        self.assertNotIn('credentials',self.store.items[self.pair['id']])

    def test_new_panel_request_invalidates_previous_pair(self):
        self.store.submit(self.pair['code'],'owner@example.invalid','TEST ONLY')
        self.store.begin('panel')
        with self.assertRaises(ValueError):self.store.poll(self.pair['id'],'panel')
        self.assertEqual(len(self.store.items),1)

    def test_invalid_body_cannot_queue(self):
        for code,email,password in [('wrong','owner@example.invalid','TEST ONLY'),(self.pair['code'],'','TEST ONLY'),(self.pair['code'],'owner@example.invalid','x'*2049),(None,None,None)]:
            with self.assertRaises(ValueError):self.store.submit(code,email,password)
        self.assertEqual(self.store.state(self.pair['code']),'waiting')


if __name__=='__main__':unittest.main()
