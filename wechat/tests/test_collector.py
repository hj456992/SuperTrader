from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from collector import parse_frame, StablePages
except ImportError:
    parse_frame = StablePages = None


def line(text, y, bubble=False, x=370, height=12):
    return dict(text=text, x=x, y=y, width=120, height=height,
                confidence=0.95, bubble=bubble)


def raw(lines, title='卧底不追高(16)', status='ok'):
    return dict(status=status, title=title, windowId=42, width=781, height=668,
                chatLeft=300, chatTop=52, chatBottom=525, lines=lines)


class ParserTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(parse_frame, 'Local OCR parser has not been implemented')

    def test_multiline_bubbles_keep_their_speaker_and_time_context(self):
        page = parse_frame(raw([line('甲', 80), line('一条长消息', 110, True, 382, 15),
                               line('的下一行', 130, True, 382, 15),
                               line('23:44', 180, False, 524),
                               line('乙', 220), line('收到', 250, True, 382, 15)]))
        self.assertEqual([(m['author'], m['text'], m['timeLabel']) for m in page['messages']],
                         [('甲', '一条长消息\n的下一行', None), ('乙', '收到', '23:44')])

    def test_wrong_group_and_error_frames_cannot_produce_messages(self):
        for item in [raw([line('秘密', 100)], title='另一个群'),
                     raw([line('秘密', 100)], status='other_chat')]:
            self.assertIsNone(parse_frame(item))

    def test_orphan_top_bubble_and_clipped_bottom_are_excluded(self):
        page = parse_frame(raw([line('上页未完整显示的消息', 54, True, 382, 15),
                               line('甲', 100), line('完整消息', 130, True, 382, 15),
                               line('乙', 505), line('被截断的消息', 520, True, 382, 15)]))
        self.assertEqual([m['text'] for m in page['messages']], ['完整消息'])

    def test_media_placeholder_does_not_invent_transcription(self):
        page = parse_frame(raw([line('甲', 100), line('图内文字', 160, False, 480, 24),
                               line('乙', 240), line('文字回复', 280, True, 382, 15)]))
        self.assertEqual(page['messages'][0]['kind'], 'media')
        self.assertEqual(page['messages'][0]['text'], '')

    def test_clipped_next_sender_does_not_drop_previous_complete_media(self):
        page = parse_frame(raw([line('甲', 400), line('乙', 513, height=10)]))
        self.assertEqual([(m['author'], m['kind']) for m in page['messages']], [('甲', 'media')])

    def test_page_must_stabilize_and_reset_on_group_switch(self):
        self.assertIsNotNone(StablePages)
        pages = StablePages()
        one = parse_frame(raw([line('甲', 80), line('文本', 110, True, 382, 15)]))
        self.assertFalse(pages.accept(one))
        self.assertTrue(pages.accept(one))
        pages.reset()
        self.assertFalse(pages.accept(one))

    def test_self_reply_does_not_inherit_previous_author_or_merge_separate_bubbles(self):
        first = line('第一条我的回复', 160, True, 590, 16)
        second = line('第二条我的回复', 215, True, 590, 16)
        first['outgoing'] = second['outgoing'] = True
        page = parse_frame(raw([line('甲', 80), line('对方原文', 110, True, 382, 16), first, second]))
        self.assertEqual([(m['author'], m['text']) for m in page['messages']],
                         [('甲', '对方原文'), ('我（窗口右侧）', '第一条我的回复'), ('我（窗口右侧）', '第二条我的回复')])

    def test_outgoing_wrap_is_one_message(self):
        first = line('换行消息的开头', 160, True, 590, 16)
        second = line('消息的下一行', 180, True, 590, 16)
        first['outgoing'] = second['outgoing'] = True
        page = parse_frame(raw([first, second]))
        self.assertEqual([m['text'] for m in page['messages']], ['换行消息的开头\n消息的下一行'])


if __name__ == '__main__':
    unittest.main()
