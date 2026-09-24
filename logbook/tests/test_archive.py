import io
from pathlib import Path
import stat
import sys
import unittest
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.archive import parse_archive, ArchiveError


def bundle(text, extras=()):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('聊天记录.txt',text)
        for name,data in extras:z.writestr(name,data)
    return out.getvalue()

TEXT='·成员甲\n2026年9月23日 01:15\n第一行\n第二行\n\n·成员乙\n2026年9月23日 01:16\n[图片] 图片_1.png\n'

class ArchiveTests(unittest.TestCase):
    def test_original_text_time_and_attachment_preserved(self):
        p=parse_archive(bundle(TEXT,[('聊天记录内的图片、视频和文件/图片_1.png',b'fake-png')]))
        self.assertEqual(len(p['messages']),2)
        self.assertEqual(p['messages'][0]['text'],'第一行\n第二行')
        self.assertEqual(p['messages'][0]['sentAtLabel'],'2026年9月23日 01:15')
        self.assertEqual(p['messages'][1]['author'],'成员乙')
        self.assertEqual(p['messages'][1]['attachments'],['聊天记录内的图片、视频和文件/图片_1.png'])
        self.assertEqual(p['files']['聊天记录内的图片、视频和文件/图片_1.png'],b'fake-png')
    def test_nested_forward_does_not_become_another_speaker(self):
        p=parse_archive(bundle('·甲\n2026年9月23日 01:15\n[聊天记录]\n    ·乙\n    2026年9月22日 09:00\n    内文\n'))
        self.assertEqual(len(p['messages']),1)
        self.assertIn('·乙',p['messages'][0]['text'])
    def test_unknown_format_and_invalid_dates_rejected(self):
        for text in ['not a transcript','·甲\n2026年2月30日 01:15\n内容\n','·甲\n2026年9月23日 25:15\n内容\n']:
            with self.subTest(text=text),self.assertRaises(ArchiveError):parse_archive(bundle(text))
    def test_traversal_and_symlink_never_extracted(self):
        for name in ['../escape','/tmp/escape','folder/../../escape','folder\\escape']:
            with self.subTest(name=name),self.assertRaises(ArchiveError):parse_archive(bundle(TEXT,[(name,b'x')]))
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:
            z.writestr('聊天记录.txt',TEXT)
            i=zipfile.ZipInfo('link');i.create_system=3;i.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(i,'/etc/passwd')
        with self.assertRaises(ArchiveError):parse_archive(out.getvalue())
    def test_ambiguous_attachment_names_are_not_guessed(self):
        p=parse_archive(bundle(TEXT,[('a/图片_1.png',b'a'),('b/图片_1.png',b'b')]))
        self.assertEqual(p['messages'][1]['attachments'],[])
    def test_conflicting_explicit_group_is_rejected(self):
        with self.assertRaises(ArchiveError):parse_archive(bundle('聊天：其他群\n'+TEXT))
    def test_fake_header_after_unparseable_date_is_not_silently_absorbed(self):
        with self.assertRaises(ArchiveError):parse_archive(bundle(TEXT+'\n·丙\n2026年9月23日 90:00\n错误时间\n'))
