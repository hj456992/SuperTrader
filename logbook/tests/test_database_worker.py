import json,tempfile,unittest
from pathlib import Path
from logbook.tests import test_database_reader as fixture
from logbook.database_worker import DatabaseWorker
from logbook.store import LogStore

class DatabaseWorkerTests(unittest.TestCase):
    setUp=fixture.DatabaseReaderTests.setUp
    add=fixture.DatabaseReaderTests.add
    def test_worker_real_encrypted_wal_restart(self):
        data=self.root/'log';store=LogStore(data);self.addCleanup(store.close)
        config=data/'database-source.json';config.write_text(json.dumps(dict(targetName='卧底不追高',dbRoot=str(self.root),keyFile=str(self.keys))));config.chmod(0o600)
        worker=DatabaseWorker(store,start=False);self.add(1)
        worker.sync_once();self.assertEqual(worker.state()['status'],'connected');self.assertEqual(store.state()['group']['messageCount'],1)
        self.add(2,'后续记录');worker.sync_once();self.assertEqual(store.state()['group']['messageCount'],2)
        restarted=DatabaseWorker(store,start=False);restarted.sync_once()
        self.assertEqual(store.state()['group']['messageCount'],2)
        self.assertTrue(restarted.state()['targetVerified'])
    def test_worker_no_key_never_claims_connected(self):
        store=LogStore(self.root/'empty');self.addCleanup(store.close)
        worker=DatabaseWorker(store,start=False);worker.sync_once()
        self.assertEqual(worker.state()['status'],'setup_required')
        self.assertEqual(store.state()['group']['messageCount'],0)

class WorkerBoundaryTests(unittest.TestCase):
    def test_changed_target_before_first_read_never_commits(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);store=LogStore(root);self.addCleanup(store.close)
            key=root/'keys.json';key.write_text('{}')
            config=root/'database-source.json';config.write_text(json.dumps(dict(targetName='卧底不追高',dbRoot=str(root),keyFile=str(key))));config.chmod(0o600)
            class ChangedReader:
                def __init__(self,*a):self.account='fixture'
                def resolve(self):return 'first@chatroom'
                def read_batch(self,checkpoint):return dict(account_fingerprint='fixture',chat_id='changed@chatroom',rows=[],checkpoint={},has_more=False)
            worker=DatabaseWorker(store,start=False)
            with patch('logbook.database_worker.DatabaseReader',ChangedReader):worker.sync_once()
            self.assertEqual(worker.state()['status'],'error')
            self.assertIsNone(store.get_database_checkpoint('fixture','first@chatroom'))
    def test_shutdown_during_read_does_not_commit(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);store=LogStore(root);self.addCleanup(store.close)
            key=root/'keys.json';key.write_text('{}')
            config=root/'database-source.json';config.write_text(json.dumps(dict(targetName='卧底不追高',dbRoot=str(root),keyFile=str(key))));config.chmod(0o600)
            worker=DatabaseWorker(store,start=False)
            class SlowReader:
                def __init__(self,*a):self.account='fixture'
                def resolve(self):return 'first@chatroom'
                def read_batch(self,checkpoint):
                    worker.stop.set()
                    return dict(account_fingerprint='fixture',chat_id='first@chatroom',rows=[],checkpoint={},has_more=False)
            with patch('logbook.database_worker.DatabaseReader',SlowReader):worker.sync_once()
            self.assertIsNone(store.get_database_checkpoint('fixture','first@chatroom'))
    def test_second_worker_cannot_acquire_same_data_directory(self):
        with tempfile.TemporaryDirectory() as d:
            store=LogStore(Path(d));self.addCleanup(store.close)
            one=DatabaseWorker(store);self.addCleanup(one.close)
            two=DatabaseWorker(store);self.addCleanup(two.close)
            self.assertIsNone(two.thread);self.assertEqual(two.state()['status'],'error')
