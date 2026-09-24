"""Integration tests use artificial encrypted databases, never personal data."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zstandard

# Fixture writer is test-only, using the existing audited SQLCipher harness.
spec = importlib.util.spec_from_file_location('fixture_cipher', Path(__file__).resolve().parents[2] / 'logbook/sqlcipher.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
KEY = 'ab' * 32
CHAT = 'toy-authorized@chatroom'
TABLE = 'Msg_' + hashlib.md5(CHAT.encode()).hexdigest()

class StructuredTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.dbdir = self.root / 'account/db_storage'
        self.dbdir.mkdir(parents=True)
        self.writers = []
        self.keys = {}
        self.contact = self.create('contact/contact.db')
        self.contact.query("CREATE TABLE contact(username TEXT, nick_name TEXT, remark TEXT);")
        self.contact.query(f"INSERT INTO contact VALUES('{CHAT}','卧底不追高',''),('sender','作者','');")
        self.db = self.create('message/message_0.db')
        self.schema(self.db)
        self.insert(self.db, 3, '重复', 1700000001)
        self.insert(self.db, 9, '重复', 1700000002)
        self.insert(self.db, 17, '长文' * 3000, 1700000003)
        self.keyfile = self.root / 'keys.json'
        self.keyfile.write_text(json.dumps({'keys': self.keys}))
        self.keyfile.chmod(0o600)
        self.config = self.root / 'config.json'
        self.cfg = {'db_dir': str(self.dbdir), 'keys_file': str(self.keyfile), 'cache_dir': str(self.root / 'cache'), 'authorized_target': '卧底不追高'}
        self.write_config()
        self.cursor = self.root / 'cursor.json'
    def create(self, rel):
        path = self.dbdir / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        db = fixture.DB(path, KEY, fixture_write=True)
        db.query('PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;')
        self.writers.append(db)
        self.keys[rel] = {'enc_key': KEY}
        return db
    def schema(self, db):
        db.query(f'CREATE TABLE {TABLE}(local_id INTEGER PRIMARY KEY, server_id INTEGER, local_type INTEGER, create_time INTEGER, real_sender_id INTEGER, message_content BLOB, WCDB_CT_message_content INTEGER); CREATE TABLE Name2Id(user_name TEXT); INSERT INTO Name2Id VALUES (\'sender\');')
        db.query('CREATE TABLE Msg_00000000000000000000000000000000(message_content TEXT); INSERT INTO Msg_00000000000000000000000000000000 VALUES(\'UNAUTHORIZED_SENTINEL\');')
    def insert(self, db, id, text, ts):
        db.query(f"INSERT INTO {TABLE} VALUES({id},9007199254740991+{id},1,{ts},1,'sender:\n{text}',0);")
    def write_config(self):
        self.config.write_text(json.dumps(self.cfg))
        self.config.chmod(0o600)
    def run_cli(self, target='卧底不追高', limit=2):
        return subprocess.run([sys.executable, '-m', 'wechat_cli.main', '--config', str(self.config), 'history', target, '--format', 'json', '--structured', '--cursor-file', str(self.cursor), '--limit', str(limit)], cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True)
    def batch(self, **kwargs):
        result = self.run_cli(**kwargs)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)
    def save_checkpoint(self, value):
        self.cursor.write_text(json.dumps(value))
        self.cursor.chmod(0o600)
    def tearDown(self):
        for db in reversed(self.writers): db.close()
        self.tmp.cleanup()
    def test_real_ids_duplicate_long_text_seconds_and_paging(self):
        first = self.batch()
        self.assertEqual(first['schema'], 'aichat.history.v1')
        self.assertEqual([r['local_id'] for r in first['rows']], [3,9])
        self.assertEqual([r['text'] for r in first['rows']], ['重复','重复'])
        self.assertEqual(first['rows'][0]['create_time'], 1700000001)
        self.assertEqual(str(first['rows'][0]['server_id']), '9007199254740994')
        self.assertEqual(first['rows'][0]['sender_id'], 'sender')
        self.assertEqual(first['rows'][0]['author'], '作者')
        self.assertTrue(first['has_more'])
        self.assertFalse(self.cursor.exists())
        self.save_checkpoint(first['checkpoint'])
        second = self.batch()
        self.assertEqual([r['local_id'] for r in second['rows']], [17])
        self.assertEqual(second['rows'][0]['text'], '长文' * 3000)
        self.assertFalse(second['has_more'])
        self.assertEqual(self.cursor.read_text(), json.dumps(first['checkpoint']))
        for path in (self.root / 'cache').rglob('*'):
            self.assertFalse(path.stat().st_mode & 0o077)
            if path.is_file(): self.assertNotIn(b'UNAUTHORIZED_SENTINEL', path.read_bytes())
    def test_exact_unique_group_and_authorization(self):
        self.assertNotEqual(self.run_cli(target='卧底').returncode, 0)
        self.contact.query("INSERT INTO contact VALUES ('duplicate@chatroom','卧底不追高','');")
        self.assertNotEqual(self.run_cli().returncode, 0)
        self.cfg['authorized_chat_id'] = CHAT
        self.write_config()
        self.assertEqual(self.batch()['chat_id'], CHAT)
        self.assertNotEqual(self.run_cli(target='someone@chatroom').returncode, 0)
    def test_multidatabase_pagination_and_missing_key_fail_closed(self):
        db = self.create('message/message_1.db')
        self.schema(db)
        self.insert(db, 1, '第二分库', 1700000000)
        self.keyfile.write_text(json.dumps({'keys': self.keys}))
        first = self.batch()
        self.save_checkpoint(first['checkpoint'])
        second = self.batch()
        self.assertEqual([(r['database'],r['local_id']) for r in second['rows']], [('message_0.db',17),('message_1.db',1)])
        self.assertFalse(second['has_more'])
        del self.keys['message/message_1.db']
        self.keyfile.write_text(json.dumps({'keys': self.keys}))
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
    def test_checkpoint_identity_salt_rollback_rejected(self):
        cp = self.batch()['checkpoint']
        for field, value in [('account','wrong'),('chat','wrong@chatroom'),('salts',{'message_0.db':'00'*16}),('cursor',{'message_0.db':999}),('cursor',{'message_0.db':-1})]:
            with self.subTest(field=field,value=value):
                bad = dict(cp); bad[field] = value
                self.save_checkpoint(bad)
                r = self.run_cli()
                self.assertNotEqual(r.returncode, 0)
                self.assertEqual(r.stdout, '')
    def test_wal_uncommitted_frames_not_read_and_sources_unchanged(self):
        self.db.query('PRAGMA cache_size=1; BEGIN;')
        for i in range(20,60): self.insert(self.db, i, 'UNCOMMITTED' * 800, 1700000100+i)
        sources = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.dbdir.rglob('*') if p.is_file()}
        b = self.batch(limit=200)
        self.assertEqual([r['local_id'] for r in b['rows']], [3,9,17])
        self.assertEqual(sources, {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.dbdir.rglob('*') if p.is_file()})
        self.db.query('COMMIT;')
        self.assertEqual(len(self.batch(limit=200)['rows']), 43)
    def test_corrupt_wal_checksum_fails_without_partial_success(self):
        wal = Path(str(self.dbdir / 'message/message_0.db') + '-wal')
        with wal.open('r+b') as stream:
            stream.seek(-60, 2)
            value = stream.read(1)
            stream.seek(-1, 1)
            stream.write(bytes([value[0] ^ 1]))
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
    def test_page_hmac_damage_fails_without_rows(self):
        page = int(self.db.query(f"SELECT rootpage FROM sqlite_master WHERE name='{TABLE}'")[0]['rootpage'])
        self.db.query('PRAGMA wal_checkpoint(TRUNCATE);')
        path = self.dbdir / 'message/message_0.db'
        with path.open('r+b') as stream:
            stream.seek((page - 1) * 4096 + 100)
            value = stream.read(1)
            stream.seek(-1, 1)
            stream.write(bytes([value[0] ^ 1]))
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
    def test_null_text_compatible_with_logbook(self):
        self.db.query(f"UPDATE {TABLE} SET message_content=NULL WHERE local_id=3")
        self.assertEqual(self.batch()['rows'][0]['text'], '')
    def test_upstream_zstd_and_message_type_parsing(self):
        compressed = zstandard.ZstdCompressor().compress('sender:\n压缩正文'.encode()).hex()
        self.db.query(f"UPDATE {TABLE} SET message_content=X'{compressed}',WCDB_CT_message_content=4 WHERE local_id=3")
        self.db.query(f"UPDATE {TABLE} SET local_type=49,message_content='sender:\n<msg><appmsg><title>文件名称.pdf</title><type>6</type></appmsg></msg>' WHERE local_id=9")
        rows = self.batch()['rows']
        self.assertEqual(rows[0]['text'], '压缩正文')
        self.assertEqual(rows[1]['text'], '[文件] 文件名称.pdf')
        self.db.query(f"UPDATE {TABLE} SET message_content=X'00',WCDB_CT_message_content=4 WHERE local_id=9")
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
    def test_invalid_key_and_insecure_config_return_no_partial_output(self):
        self.keys['message/message_0.db'] = {'enc_key': 'cd'*32}
        self.keyfile.write_text(json.dumps({'keys':self.keys}))
        r = self.run_cli(); self.assertNotEqual(r.returncode, 0); self.assertEqual(r.stdout, '')
        self.config.chmod(0o644)
        self.assertNotEqual(self.run_cli().returncode, 0)

if __name__ == '__main__': unittest.main()
