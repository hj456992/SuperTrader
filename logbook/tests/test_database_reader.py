import hashlib,json,os,tempfile,unittest
from pathlib import Path
from logbook.db_reader import DatabaseReader, DatabaseReadError
from logbook.sqlcipher import DB

class DatabaseReaderTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/'contact').mkdir();(self.root/'message').mkdir()
        self.chat='fixture-only@chatroom';self.table='Msg_'+hashlib.md5(self.chat.encode()).hexdigest()
        self.key=os.urandom(32).hex();self.keys=self.root/'keys.json'
        self.keys.write_text(json.dumps({'keys':{x:{'enc_key':self.key} for x in ['contact/contact.db','message/message_0.db']}}));self.keys.chmod(0o600)
        with DB(self.root/'contact/contact.db',self.key,fixture_write=True) as db:
            db.query('CREATE TABLE contact(username TEXT,nick_name TEXT,remark TEXT)')
            db.query("INSERT INTO contact VALUES('fixture-only@chatroom','卧底不追高',''),('peer','测试成员','')")
        self.writer=DB(self.root/'message/message_0.db',self.key,fixture_write=True);self.addCleanup(self.writer.close)
        self.writer.query('PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0')
        self.writer.query('CREATE TABLE Name2Id(user_name TEXT)');self.writer.query("INSERT INTO Name2Id VALUES('peer')")
        self.writer.query(f'CREATE TABLE "{self.table}"(local_id INTEGER,server_id INTEGER,local_type INTEGER,create_time INTEGER,real_sender_id INTEGER,message_content BLOB)')
        self.reader=DatabaseReader(self.root,self.keys)
    def add(self,n,body='测试原文',kind=1):
        value=('peer:\n'+body).encode().hex()
        self.writer.query(f'''INSERT INTO "{self.table}" VALUES({n},{100+n},{kind},{1700000000+n},1,x'{value}')''')
    def test_target_only_wal_and_restart_checkpoint(self):
        self.add(1);batch=self.reader.read_batch(None)
        self.assertEqual(batch['chat_id'],self.chat);self.assertEqual(batch['rows'][0]['text'],'测试原文');self.assertEqual(batch['rows'][0]['author'],'测试成员')
        checkpoint=batch['checkpoint'];self.assertEqual(self.reader.read_batch(checkpoint)['rows'],[])
        self.add(2,'新消息仅在WAL');next_batch=self.reader.read_batch(checkpoint)
        self.assertEqual([r['local_id'] for r in next_batch['rows']],[2])
        self.assertEqual(next_batch['rows'][0]['text'],'新消息仅在WAL')
    def test_wrong_group_duplicate_name_and_wrong_key_fail(self):
        with self.assertRaises(DatabaseReadError):DatabaseReader(self.root,self.keys,target='其他群')
        with DB(self.root/'contact/contact.db',self.key,fixture_write=True) as db:db.query("INSERT INTO contact VALUES('another@chatroom','卧底不追高','')")
        with self.assertRaises(DatabaseReadError):self.reader.read_batch(None)
    def test_unknown_type_and_cursor_regression(self):
        self.add(1,'不可当成文字',3);batch=self.reader.read_batch(None)
        self.assertIsNone(batch['rows'][0]['text'])
        bad=dict(batch['checkpoint']);bad['cursor']={'message_0.db':999}
        with self.assertRaises(DatabaseReadError):self.reader.read_batch(bad)
    def test_different_account_or_salt_checkpoint_rejected(self):
        self.add(1);batch=self.reader.read_batch(None)
        for key,value in [('account','wrong'),('salts',{'message_0.db':'bad'})]:
            checkpoint=dict(batch['checkpoint']);checkpoint[key]=value
            with self.assertRaises(DatabaseReadError):self.reader.read_batch(checkpoint)

if __name__=='__main__':unittest.main()
