from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from logbook.archive import TARGET
from logbook.store import LogStore
from test_archive import bundle, TEXT


def row(local_id=1, **changes):
    result = dict(database='message_0.db', local_id=local_id,
                  server_id=900000000000000001, sender_id='wxid_a',
                  author='同名', text='原文', create_time=1790100123, local_type=1)
    result.update(changes)
    return result


class DatabaseStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = LogStore(self.root)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def add(self, rows, checkpoint=None, account='account-a', chat='target@chatroom', group=TARGET):
        return self.store.import_database_batch(account, chat, group, rows,
                                                checkpoint if checkpoint is not None else {'next': 2})

    def messages(self):
        return [m for s in self.store.state()['segments'] for m in s['messages']]

    def test_replay_survives_restart_and_checkpoint_is_persisted(self):
        self.assertTrue(callable(getattr(self.store, 'import_database_batch', None)),
                        'Database ingestion API must exist')
        self.assertEqual(self.add([row()])['added'], 1)
        self.store.close()
        self.store = LogStore(self.root)
        self.assertEqual(self.add([row()])['added'], 0)
        self.assertEqual(len(self.messages()), 1)
        self.assertEqual(len(self.store.state()['segments']), 1)
        self.assertEqual(self.store.get_database_checkpoint('account-a', 'target@chatroom'), {'next': 2})

    def test_target_restriction_and_account_database_isolation(self):
        with self.assertRaises(ValueError):
            self.add([row()], group='其他群')
        self.assertIsNone(self.store.get_database_checkpoint('account-a', 'target@chatroom'))
        self.add([row()])
        self.add([row()], account='account-b')
        self.add([row(database='message_1.db')])
        self.assertEqual(len(self.messages()), 3)

    def test_sender_identity_and_second_precision_and_unknown_server_id(self):
        self.add([row(), row(2, sender_id='wxid_b', server_id=0),
                  row(3, author='已改名')])
        messages = self.messages()
        self.assertEqual(len(self.store.state()['members']), 2)
        self.assertNotEqual(messages[0]['memberId'], messages[1]['memberId'])
        self.assertEqual(messages[0]['memberId'], messages[2]['memberId'])
        self.assertEqual(messages[0]['sourceMessageId'], '900000000000000001')
        self.assertIsNone(messages[1]['sourceMessageId'])
        self.assertEqual(messages[0]['createTime'], 1790100123)
        self.assertEqual(messages[0]['timePrecision'], 'second')
        self.assertEqual(messages[0]['text'], '原文')
        self.assertEqual(messages[0]['source'], 'wechat_database')

    def test_non_text_never_exposes_encoded_payload_as_chat_body(self):
        self.add([row(local_type=3, text='<private encoded payload>'),
                  row(2, local_type=999, text=None)])
        messages = self.messages()
        self.assertEqual(messages[0]['kind'], 'image')
        self.assertEqual(messages[0]['text'], '[图片]')
        self.assertEqual(messages[1]['text'], '[消息类型 999]')
        with self.assertRaises(ValueError):
            self.add([row(3, text=None)])

    def test_state_limits_recent_database_rows_without_losing_saved_total(self):
        self.add([row(i, create_time=1790100000+i) for i in range(2001)], {'next': 2001})
        state = self.store.state()
        messages = self.messages()
        self.assertEqual(state['group']['messageCount'], 2001)
        self.assertEqual(len(messages), 2000)
        self.assertEqual(messages[0]['sourceLocalId'], 1)
        self.assertEqual(state['group']['identity'], 'database_id')
        self.assertIn('2000', state['coverage']['description'])

    def test_database_error_rolls_back_messages_and_checkpoint_together(self):
        self.add([row()], {'next': 2})
        self.store.db.executescript("""
            CREATE TRIGGER fail_second BEFORE INSERT ON database_messages
            WHEN NEW.local_id = 3 BEGIN SELECT RAISE(ABORT, 'simulated disk write failure'); END;
        """)
        with self.assertRaises(sqlite3.IntegrityError):
            self.add([row(2), row(3)], {'next': 4})
        self.assertEqual(len(self.messages()), 1)
        self.assertEqual(self.store.get_database_checkpoint('account-a', 'target@chatroom'), {'next': 2})
        self.assertEqual(len(self.store.state()['segments']), 1)

    def test_source_status_and_empty_checkpoint_and_legacy_export_coexist(self):
        self.store.import_archive(bundle(TEXT), 'original.zip', TARGET)
        self.assertEqual(self.store.state()['source']['type'], 'wechat_export')
        self.store.set_database_source('account-a', 'target@chatroom', TARGET,
                                       status='waiting', detail='等待本机数据库')
        self.assertEqual(self.store.state()['source']['status'], 'waiting')
        self.assertEqual(self.store.state()['source']['type'], 'wechat_database')
        self.add([], {'next': 0})
        self.assertEqual(len(self.store.state()['segments']), 1)
        self.add([row()], {'next': 2})
        self.store.set_database_source('account-a', 'target@chatroom', TARGET, status='connected')
        state = self.store.state()
        self.assertEqual(state['group']['messageCount'], 3)
        self.assertEqual(state['group']['identity'], 'mixed')
        self.assertEqual(state['source']['status'], 'connected')
        self.assertNotIn('detail', state['source'])
        self.assertEqual({m['source'] for m in self.messages()}, {'wechat_export', 'wechat_database'})
        self.assertEqual(state['segments'][-1]['gapBefore'], 'database_batch')


if __name__ == '__main__':
    unittest.main()
