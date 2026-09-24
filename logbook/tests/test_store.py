from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.store import LogStore
from test_archive import bundle,TEXT

class LogTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)/'data';self.store=LogStore(self.root)
    def tearDown(self):self.store.close();self.temp.cleanup()
    def test_same_archive_idempotent_after_restart(self):
        data=bundle(TEXT)
        self.assertEqual(self.store.import_archive(data,'a.zip','卧底不追高')['added'],2)
        self.store.close();self.store=LogStore(self.root)
        self.assertEqual(self.store.import_archive(data,'renamed.zip','卧底不追高')['added'],0)
        s=self.store.state();self.assertEqual(s['group']['messageCount'],2)
        self.assertEqual(s['stats']['duplicates'],1)
        self.assertIsNone(s['segments'][0]['messages'][0]['sourceMessageId'])
    def test_repeated_messages_within_and_between_distinct_batches_preserved(self):
        text='·甲\n2026年9月23日 01:15\n重复\n\n·甲\n2026年9月23日 01:15\n重复\n'
        self.store.import_archive(bundle(text),'one.zip','卧底不追高')
        self.store.import_archive(bundle(text+'\n·乙\n2026年9月23日 01:16\n后续\n'),'two.zip','卧底不追高')
        self.assertEqual(self.store.state()['group']['messageCount'],5)
        self.assertEqual(len(self.store.state()['segments']),2)
    def test_wrong_target_cannot_enter_store(self):
        with self.assertRaises(ValueError):self.store.import_archive(bundle(TEXT),'a.zip','其他群')
        self.assertEqual(self.store.state()['group']['messageCount'],0)
    def test_attachment_is_private_and_downloadable_by_opaque_id(self):
        self.store.import_archive(bundle(TEXT,[('图片_1.png',b'actual-attachment')]),'a.zip','卧底不追高')
        s=self.store.state();a=s['segments'][0]['messages'][1]['attachments'][0]
        path,name=self.store.attachment(a['id']);self.assertEqual(path.read_bytes(),b'actual-attachment')
        self.assertEqual(name,'图片_1.png');self.assertEqual(path.stat().st_mode&0o777,0o600)
        with self.assertRaises(KeyError):self.store.attachment('../no')
