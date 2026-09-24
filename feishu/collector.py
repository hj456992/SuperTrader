#!/usr/bin/env python3
"""Feishu conversation adapter. Lists, selects and reads; never sends messages."""
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

ERRORS = {
 'permission_required':'请在 macOS 辅助功能中允许运行爱聊的应用读取飞书，然后重试。',
 'not_running':'请先打开并登录飞书客户端。',
 'no_chat':'请在飞书中打开要读取的聊天，再点击读取。',
 'empty':'当前页面没有可读取的消息，请在飞书中滚动到消息位置后重试。',
 'error':'读取飞书未完成，请稍后重试。',
 'not_found':'未能打开所选会话，请刷新列表后重试。',
 'ambiguous':'会话存在歧义，请在飞书中选择后使用读取当前会话。',
}
CONTROL = re.compile(r'^(回复话题|展开|收起|新消息|查看更早.*话题回复|\d+ 条回复|\d+ 条新消息)$')

def children(n):return n.get('children', [])
def walk(n):
 yield n
 for c in children(n):yield from walk(c)
def value(n):return str(n.get('value') or n.get('title') or n.get('description') or '').strip()
def static(n):return n.get('role') in ('AXStaticText','AXText','AXLink')
def texts(n):
 if n.get('role') in ('AXTextArea','AXTextField','AXButton'):return []
 if static(n):
  v=value(n)
  return [v] if v and not CONTROL.fullmatch(v) else []
 if n.get('role')=='AXImage':
  v=value(n)
  return ['[图片]'] if v=='[图片]' or v.startswith(('/image','http')) else []
 return [t for c in children(n) for t in texts(c)]
def is_reactions(n):
 while n.get("role")=="AXGroup" and len(children(n))==1:n=children(n)[0]
 cs=children(n)
 if len(cs)<2:return False
 first=list(walk(cs[0]))
 return (not any(static(x) and value(x) for x in first)
         and any(x.get('role')=='AXImage' and value(x) and value(x)!='[图片]' for x in first)
         and any(re.fullmatch(r'[A-Z][A-Z_]+',str(x.get('domId',''))) for x in first))

def message_rows(root):
 # Message IDs are exposed by Feishu itself. Never infer message boundaries from
 # sibling counts: pinned banners and topic previews share the same layout.
 for n in children(root):
  if re.fullmatch(r'\d{15,22}',str(n.get('domId',''))):yield n
  else:yield from message_rows(n)

def utf16len(text):return len(text.encode('utf-16-le'))//2
def utf16head(text,units):return text.encode('utf-16-le')[:units*2].decode('utf-16-le',errors='ignore')

def fail(status,detail=None):return {'status':status,'detail':detail or ERRORS.get(status,ERRORS['error'])}

def parse_snapshot(snapshot):
 status=snapshot.get('status','error')
 if status!='ok':return fail(status,{'stale_list':'会话列表已变化，请重新加载列表后选择。','switch_timeout':'飞书未切换到所选会话，请重试。','activation_failed':'所选会话暂不可点击，请在飞书显示会话列表后重试。'}.get(snapshot.get('reason')))
 root=snapshot.get('root',{})
 if root.get('role')!='AXWebArea' or (root.get('title') or root.get('description'))!='messenger-chat':return fail('no_chat')
 title=next((value(n) for n in walk(root) if static(n) and value(n)), '')
 if not title:return fail('no_chat')
 if utf16len(title)>100:return fail('error','会话名称超过当前保存上限（100 字符），请先在飞书中选择名称较短的会话。')
 messages=[]
 for row in message_rows(root):
  identifier=row['domId']
  inner=next((n for n in walk(row) if n is not row and n.get('domId')==identifier),None)
  if inner is None:continue
  prefix=[]
  for n in walk(row):
   if n is inner:break
   if static(n) and value(n):prefix.append(value(n))
  sender=prefix[0] if prefix else '发言人未显示'
  if len(sender)>100 or CONTROL.fullmatch(sender):sender='发言人未显示'
  content=' '.join(t for index,part in enumerate(children(inner)) if index==0 or not is_reactions(part) for t in texts(part)).strip()
  for n in walk(row):
   if any(static(c) and value(c)=='回复话题' for c in children(n)):
    replies=' | '.join(' '.join(texts(c)) for c in children(n) if texts(c))
    if replies:content+=' 【话题回复预览】'+replies
  if content.strip():messages.append({'sender':sender,'text':content.strip(),'role':'note','sourceId':identifier})
 if not messages:return fail('empty')
 selected=[];lines=[];total=0;stored_size=2;truncated=False
 for message in messages:
  # A message is one parser line even if its body contains a role-like prefix.
  content=' ／ '.join(message['text'].splitlines())
  line='备注：飞书原文｜'+message['sender']+'：'+content
  if utf16len(line)>3900:
   line=utf16head(line,3850)+'…【本条过长，预览已截断】';truncated=True
  estimate={'id':'E-00000000','role':'note','text':line.split('：',1)[1],'importedAt':'2026-09-23T01:00:00.123456789Z','sender':message['sender'],'source':'feishu_desktop'}
  size=utf16len(json.dumps(estimate,ensure_ascii=False,separators=(',',':')))+1
  if len(selected)>=60 or total+utf16len(line)+1>16000 or stored_size+size>23000:
   truncated=True;break
  selected.append(message);lines.append(line);total+=utf16len(line)+1;stored_size+=size
 return {'status':'ok','platform':'feishu','chatTitle':title,'text':'\n'.join(lines),
         'messages':selected,'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'scope':'current_view','truncated':truncated,
         'detail':'已读取飞书当前加载的消息；含可见话题预览，不代表全部历史。发言人按原文保留。'}

def parse_chats(snapshot):
 if snapshot.get('status')!='ok':return fail(snapshot.get('status','error'))
 root=snapshot.get('root',{})
 if root.get('role')!='AXWebArea' or (root.get('title') or root.get('description'))!='messenger':return fail('no_chat')
 def paths(n,path='r'):
  yield path,n
  for i,c in enumerate(children(n)):yield from paths(c,path+'_'+str(i))
 chats=[]
 timestamp=re.compile(r'(?:\d{1,2}:\d{2}|(?:昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天])(?:\s*\d{1,2}:\d{2})?|\d{1,4}[年/.-]\d{1,2}(?:[月/.-]\d{1,2}日?)?|\d{1,2}月\d{1,2}日)')
 for path,n in paths(root):
  cs=children(n)
  if n.get('role')!='AXGroup' or len(cs)<4:continue
  avatar=texts(cs[0])
  if any(not re.fullmatch(r'\d+',v) for v in avatar):continue
  labels=[(p,value(x)) for p,x in paths(cs[1],path+'_1') if static(x) and value(x)]
  if len(labels)!=1:continue
  identifier,title=labels[0]
  if utf16len(title)>100 or len(identifier)>160:continue
  marker=texts(cs[2])
  time=texts(cs[3]) if marker in (['外部'],['机器人'],['官方']) else marker
  if len(time)!=1 or not timestamp.fullmatch(time[0]):continue
  chats.append({'id':identifier,'title':title})
  if len(chats)>=200:break
 if not chats:return fail('empty','飞书当前没有可选择的会话，请打开消息列表后重试。')
 return {'status':'ok','platform':'feishu','scope':'loaded_chats','chats':chats,'detail':'仅显示飞书客户端当前已加载的会话；切换后读取当前加载的消息。'}

def main():
 try:
  args=sys.argv[1:]
  if args and args[0] not in ('list','select'):raise ValueError('action')
  if args and args[0]=='select' and (len(args)!=3 or not re.fullmatch(r'[A-Za-z0-9_-]{1,160}',args[1]) or not args[2]):raise ValueError('selection')
  result=subprocess.run([str(Path(__file__).parent/'bin/feishu-read-current'),*args],capture_output=True,timeout=18,check=True)
  if len(result.stdout)>2_000_000:raise ValueError('oversize')
  snapshot=json.loads(result.stdout)
  if args and args[0]=='list':
   output=parse_chats(snapshot)
  else:
   output=parse_snapshot(snapshot)
   if args and args[0]=='select' and output.get('status')=='ok' and output.get('chatTitle')!=args[2]:output=fail('not_found')
 except Exception:output=fail('error')
 print(json.dumps(output,ensure_ascii=False))
if __name__=='__main__':main()
