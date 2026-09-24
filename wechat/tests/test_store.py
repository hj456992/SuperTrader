import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from store import CaptureStore
except ImportError:
    CaptureStore = None


def message(text, author='甲'):
    return dict(author=author, text=text, kind='text', confidence=0.98, timeLabel=None)


def frame(*texts, title='卧底不追高 (16)'):
    return dict(title=title, windowId=123, messages=[message(t) for t in texts])


class CaptureStoreTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(CaptureStore, 'Persistent capture store has not been implemented')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'private' / 'capture.sqlite3'
        self.store = CaptureStore(self.path)
        self.addCleanup(self.store.close)

    def texts(self):
        return [[m['text'] for m in s['messages']] for s in self.store.state()['segments']]

    def test_wrong_group_never_enters_storage(self):
        for title in ['其他群', '卧底不追高的朋友', '卧底不追高 (16) extra']:
            with self.assertRaises(ValueError):
                self.store.ingest(frame('私密资料', title=title))
        self.assertEqual(self.store.state()['group']['messageCount'], 0)
        self.assertEqual(self.store.state()['stats']['observations'], 0)

    def test_repeated_stable_frame_is_idempotent_after_reopen(self):
        self.store.ingest(frame('第一句话', '第二句话', '第三句话'))
        self.store.close()
        self.store = CaptureStore(self.path)
        self.addCleanup(self.store.close)
        self.store.ingest(frame('第一句话', '第二句话', '第三句话'))
        self.assertEqual(self.texts(), [['第一句话', '第二句话', '第三句话']])
        self.assertEqual(self.store.state()['stats']['duplicates'], 1)

    def test_downward_overlap_appends_only_new_messages(self):
        self.store.ingest(frame('前一句', '中间一句', '后一句'))
        self.store.ingest(frame('中间一句', '后一句', '新的一句'))
        self.assertEqual(self.texts(), [['前一句', '中间一句', '后一句', '新的一句']])

    def test_upward_overlap_prepends_older_messages(self):
        self.store.ingest(frame('中间一句', '后一句', '最后一句'))
        self.store.ingest(frame('最早一句', '前一句', '中间一句', '后一句'))
        self.assertEqual(self.texts(), [['最早一句', '前一句', '中间一句', '后一句', '最后一句']])

    def test_real_repeated_utterances_survive_overlap(self):
        self.store.ingest(frame('开始', '好的', '补充背景', '好的'))
        self.store.ingest(frame('补充背景', '好的', '好的', '结束'))
        self.assertEqual(self.texts(), [['开始', '好的', '补充背景', '好的', '好的', '结束']])

    def test_unanchored_history_is_separate_with_gap(self):
        self.store.ingest(frame('第一段开始', '第一段结束'))
        self.store.ingest(frame('第二段开始', '第二段结束'))
        state = self.store.state()
        self.assertEqual(len(state['segments']), 2)
        self.assertTrue(state['segments'][1]['gapBefore'])
        self.assertFalse(state['coverage']['complete'])

    def test_repeated_generic_anchor_does_not_silently_merge(self):
        self.store.ingest(frame('好的', '好的', '结束'))
        self.store.ingest(frame('之前', '好的', '好的'))
        self.assertEqual(len(self.store.state()['segments']), 2)

    def test_member_names_and_unknown_timestamps_are_preserved(self):
        self.store.ingest(dict(title='卧底不追高（16）', windowId=123,
                              messages=[message('同一段话', '甲'), message('同一段话', '乙')]))
        state = self.store.state()
        self.assertEqual(state['group']['memberCount'], 2)
        msgs = state['segments'][0]['messages']
        self.assertNotEqual(msgs[0]['memberId'], msgs[1]['memberId'])
        self.assertIsNone(msgs[0]['sourceMessageId'])
        self.assertIsNone(msgs[0]['sentAt'])
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_same_body_at_different_visible_times_is_not_collapsed(self):
        first = frame('收到', '知道了')
        second = frame('收到', '知道了')
        for item in first['messages']:
            item['timeLabel'] = '10:00'
        for item in second['messages']:
            item['timeLabel'] = '11:00'
        self.store.ingest(first)
        self.store.ingest(second)
        self.assertEqual(self.store.state()['group']['messageCount'], 4)

    def test_longer_overlap_does_not_override_another_possible_alignment(self):
        self.store.ingest(frame('a', 'b', 'c', 'd', 'a', 'b'))
        result = self.store.ingest(frame('a', 'b', 'c'))
        self.assertEqual(result['added'], 3)
        self.assertEqual(len(self.store.state()['segments']), 2)

    def test_identical_page_after_intervening_content_is_preserved_as_ambiguous(self):
        self.store.ingest(frame('再次出现的内容'))
        self.store.ingest(frame('中间经过的其他内容'))
        result = self.store.ingest(frame('再次出现的内容'))
        self.assertEqual(result['added'], 1)
        self.assertEqual(len(self.store.state()['segments']), 3)

    def test_recorded_native_observation_keeps_capture_time(self):
        page = frame('先前已实际采集的页面')
        page['capturedAt'] = '2026-09-23T00:50:00+08:00'
        self.store.ingest(page)
        self.assertEqual(self.store.state()['segments'][0]['messages'][0]['capturedAt'], page['capturedAt'])


if __name__ == '__main__':
    unittest.main()
