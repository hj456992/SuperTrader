import unittest,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from collector import parse_chats

def t(v):return {'role':'AXStaticText','value':v,'children':[]}
def g(*c):return {'role':'AXGroup','children':list(c)}
def row(title,unread='12'):
 return g(g(g(t(unread))),g(t(title)),g(g(t('外部'))),g(t('19:00')),t('发言人'),t(':'),t('摘要不能当作标题'))
def snap(*rows):return {'status':'ok','root':{'role':'AXWebArea','title':'messenger','children':[g(g(t('邀请同事')),g(*rows))]}}
class ChatTests(unittest.TestCase):
 def test_rows_have_exact_title_and_title_element_path(self):
  r=parse_chats(snap(row('群 A'),row('群 B')))
  self.assertEqual(r['status'],'ok');self.assertEqual([x['title'] for x in r['chats']],['群 A','群 B'])
  self.assertEqual(r['chats'][0]['id'],'r_0_1_0_1_0')
 def test_banner_unread_counts_and_non_rows_excluded(self):
  r=parse_chats(snap(g(t('99'),g(t('固定标签'))),row('目标群')))
  self.assertEqual([x['title'] for x in r['chats']],['目标群'])
 def test_unsupported_or_empty_sidebar_returns_error(self):
  self.assertEqual(parse_chats({'status':'not_running'})['status'],'not_running')
  self.assertNotEqual(parse_chats(snap())['status'],'ok')
  s=snap(row('群'));s['root']['title']='messenger-chat'
  self.assertNotEqual(parse_chats(s)['status'],'ok')
 def test_title_utf16_limit_and_list_limit(self):
  r=parse_chats(snap(row('😀'*60),*[row('群'+str(i)) for i in range(210)]))
  self.assertEqual(len(r['chats']),200)
  self.assertNotIn('😀'*60,[x['title'] for x in r['chats']])
if __name__=='__main__':unittest.main()
