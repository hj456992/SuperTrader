import sys, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from collector import parse_snapshot

def text(value):return {'role':'AXStaticText','value':value,'children':[]}
def group(*nodes):return {'role':'AXGroup','children':list(nodes)}
def snapshot(rows,title='测试群'):
 converted=[]
 for i,row in enumerate(rows):
  cs=row['children'];named=cs and cs[0].get('role')=='AXStaticText'
  header=group(cs[0]) if named else group()
  body=cs[1:] if named else cs
  parts=[p for n in body for p in (n.get('children') or [n])]
  main=[];replies=[]
  def hasreply(n):return n.get('value')=='回复话题' or any(hasreply(c) for c in n.get('children',[]))
  for part in parts:(replies if hasreply(part) else main).append(part)
  identifier=str(7600000000000000000+i)
  inner=dict(group(*main),domId=identifier)
  converted.append(dict(group(group(),group(header,group(inner,*replies))),domId=identifier))
 return {'status':'ok','root':{'role':'AXWebArea','title':'messenger-chat','children':[group(group(text(title),{'role':'AXButton','title':'12'}),group(group(*converted)),{'role':'AXTextArea','value':'不要导入草稿','children':[]})]}}

class ParsingTests(unittest.TestCase):
 def test_dom_message_ids_exclude_pinned_banner_and_wrappers(self):
  def ident(i,*nodes):return dict(group(*nodes),domId=i)
  body=ident('7688537311113399518',group(group(text('真实正文'))))
  row=ident('7688537311113399518',group(),group(group(group(text('甲'))),group(body,group(text('回复话题')))))
  pin=group(group(text('置顶人'),group(text('公告'))),group(text('由'),group(text('置顶'))))
  snap={'status':'ok','root':{'role':'AXWebArea','title':'messenger-chat','children':[group(text('测试群'),{'role':'AXButton'}),pin,group(row)]}}
  result=parse_snapshot(snap)
  self.assertEqual([(m['sender'],m['text']) for m in result['messages']],[('甲','真实正文')])
 def test_preserves_senders_and_multiline_with_role_like_text(self):
  result=parse_snapshot(snapshot([group(text('张三'),group(text('第一行\n我：这仍是原消息'))),group(text('李四'),group(text('第二条')))]))
  self.assertEqual(result['status'],'ok')
  self.assertEqual(result['chatTitle'],'测试群')
  self.assertEqual([m['sender'] for m in result['messages']],['张三','李四'])
  self.assertIn('张三：第一行 ／ 我：这仍是原消息',result['text'])
  self.assertNotIn('不要导入草稿',result['text'])
 def test_missing_sender_is_not_guessed_and_controls_excluded(self):
  rows=[group(text('张三'),group(text('原文'),group(text('回复话题')))),group(group(text('接着说')))]
  result=parse_snapshot(snapshot(rows))
  self.assertEqual(len(result['messages']),2)
  self.assertEqual(result['messages'][1]['sender'],'发言人未显示')
  self.assertNotIn('回复话题',result['text'])
 def test_thread_preview_is_distinguished_and_image_url_not_leaked(self):
  row=group(text('张三'),group(text('主题正文'),{'role':'AXImage','description':'/image?key=private&crypto=secret'},group(group(text('李四 回复正文')),text('回复话题'))))
  result=parse_snapshot(snapshot([row]))
  self.assertEqual(result['status'],'ok')
  self.assertIn('[图片]',result['text'])
  self.assertNotIn('crypto',result['text'])
  self.assertIn('话题回复预览',result['text'])
 def test_empty_and_wrong_webarea_fail_closed(self):
  self.assertEqual(parse_snapshot(snapshot([]))['status'],'empty')
  snap=snapshot([group(text('张三'),group(text('消息')))])
  snap['root']['title']='messenger'
  self.assertEqual(parse_snapshot(snap)['status'],'no_chat')
 def test_title_over_backend_utf16_limit_fails_before_preview(self):
  r=parse_snapshot(snapshot([group(text('甲'),group(text('正文')))],title='😀'*60))
  self.assertNotEqual(r['status'],'ok')
 def test_reaction_members_are_not_message_body(self):
  i='7600000000000000001'
  reaction=group(group(dict(group({'role':'AXImage','description':'赞'}),domId='THUMBSUP')),group(text('点赞人')))
  inner=dict(group(group(text('原文')),group(group(reaction))),domId=i)
  row=dict(group(group(text('发言人')),group(inner)),domId=i)
  snap={'status':'ok','root':{'role':'AXWebArea','title':'messenger-chat','children':[text('群'),row]}}
  self.assertEqual(parse_snapshot(snap)['messages'][0]['text'],'原文')
 def test_emoji_limits_match_backend_utf16(self):
  r=parse_snapshot(snapshot([group(text('甲'),group(text('😀'*3000))) for _ in range(10)]))
  self.assertTrue(r['truncated'])
  self.assertLessEqual(len(r['text'].encode('utf-16-le'))//2,16000)
  self.assertTrue(all(len(line.encode('utf-16-le'))//2<4000 for line in r['text'].splitlines()))
 def test_bounds_are_whole_messages_and_status_propagates(self):
  r=parse_snapshot(snapshot([group(text('甲'),group(text('文'*300))) for _ in range(70)]))
  self.assertLessEqual(len(r['messages']),60)
  self.assertLessEqual(len(r['text']),16000)
  self.assertTrue(r['truncated'])
  self.assertEqual(parse_snapshot({'status':'permission_required'})['status'],'permission_required')

if __name__=='__main__':unittest.main()
