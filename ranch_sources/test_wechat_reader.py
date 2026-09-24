import importlib.util
from pathlib import Path
import sqlite3
import unittest

spec=importlib.util.spec_from_file_location('wechat_reader',Path(__file__).with_name('wechat_reader.py'))
reader=importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)

class ReaderTests(unittest.TestCase):
 def connection(self,chat):
  conn=sqlite3.connect(':memory:');self.addCleanup(conn.close)
  conn.execute('CREATE TABLE Name2Id(user_name TEXT)')
  conn.executemany('INSERT INTO Name2Id(rowid,user_name) VALUES (?,?)',[(1,'friend'),(2,'author'),(3,'room@chatroom')])
  table=reader.message_table(chat)
  conn.execute(f'CREATE TABLE [{table}](local_id INTEGER,server_id INTEGER,local_type INTEGER,create_time INTEGER,real_sender_id INTEGER,message_content BLOB,WCDB_CT_message_content INTEGER)')
  return conn,table
 def add(self,conn,table,local,server,time,sender,text):
  conn.execute(f'INSERT INTO [{table}] VALUES(?,?,?,?,?,?,?)',(local,server,1,time,sender,text.encode(),0))
 def test_catalog_lists_beyond_twenty_without_previews(self):
  conn=sqlite3.connect(':memory:');self.addCleanup(conn.close)
  conn.execute('CREATE TABLE SessionTable(username TEXT,last_timestamp INTEGER,summary TEXT)')
  conn.executemany('INSERT INTO SessionTable VALUES(?,?,?)',[(f'chat{i}',i,'private preview') for i in range(35)])
  chats,total=reader.session_catalog(conn,{})
  self.assertEqual(35,total);self.assertEqual(35,len(chats));self.assertEqual('chat34',chats[0]['id'])
  self.assertEqual({'id','title','isGroup'},set(chats[0]));self.assertNotIn('private preview',str(chats))
 def test_private_sender_is_not_discarded_or_inferred_as_self(self):
  conn,table=self.connection('friend');self.add(conn,table,1,100,1000,1,'标题:\n正文');self.add(conn,table,2,101,1001,2,'回应')
  rows,total=reader.chat_messages(conn,'friend',{},'account','message_0.db')
  self.assertEqual(2,total);self.assertEqual('friend',rows[1]['memberId']);self.assertEqual('标题:\n正文',rows[1]['text']);self.assertEqual('author',rows[0]['memberId']);self.assertFalse(any(m['isMe'] for m in rows))
 def test_group_real_sender_wins_and_group_fallback_uses_prefix(self):
  conn,table=self.connection('room@chatroom');self.add(conn,table,1,100,1000,2,'author:\n你好');self.add(conn,table,2,101,1001,3,'friend:\n晚上好')
  rows,total=reader.chat_messages(conn,'room@chatroom',{'author':'作者'},'account','message_0.db')
  self.assertEqual('friend',rows[0]['memberId']);self.assertEqual('晚上好',rows[0]['text']);self.assertEqual('author',rows[1]['memberId']);self.assertEqual('作者',rows[1]['memberName'])
 def test_newest_across_shards_and_stable_dedup(self):
  c1,t1=self.connection('friend');c2,t2=self.connection('friend')
  self.add(c1,t1,1,101,300,1,'最新');self.add(c1,t1,2,0,100,1,'较早');self.add(c2,t2,9,101,300,1,'最新');self.add(c2,t2,2,0,200,1,'中间')
  first,_=reader.chat_messages(c1,'friend',{},'account','message_0.db');second,_=reader.chat_messages(c2,'friend',{},'account','message_1.db')
  merged=reader.newest_messages(first+second,2)
  self.assertEqual(['中间','最新'],[m['text'] for m in merged]);self.assertEqual(first[0]['id'],second[0]['id']);self.assertNotEqual(first[1]['id'],second[1]['id'])
 def test_selected_chat_only_and_unknown_sender_stays_unknown(self):
  conn,table=self.connection('friend');self.add(conn,table,1,0,100,999,'不是身份线索')
  rows,_=reader.chat_messages(conn,'friend',{},'account','message_0.db');self.assertEqual('',rows[0]['memberId']);self.assertFalse(rows[0]['isMe'])
  missing,total=reader.chat_messages(conn,'unselected',{},'account','message_0.db');self.assertEqual([],missing);self.assertEqual(0,total)

 def test_unknown_group_prefix_does_not_invent_member_identity(self):
  conn,table=self.connection('room@chatroom');self.add(conn,table,1,0,100,999,'Topic:\n正文')
  rows,_=reader.chat_messages(conn,'room@chatroom',{},'account','message_0.db')
  self.assertEqual('',rows[0]['memberId']);self.assertEqual('Topic:\n正文',rows[0]['text'])

 def test_fallback_catalog_matches_contact_hashes_without_reading_messages(self):
  conn,table=self.connection('friend');self.add(conn,table,1,1,100,1,'private body')
  chats=reader.message_catalog(conn,{'friend':'联系人','other':'没有消息表'})
  self.assertEqual([{'id':'friend','title':'联系人','isGroup':False}],chats)
  self.assertNotIn('private body',str(chats))
 def test_missing_shard_keys_report_partial_scope(self):
  detail=reader.availability_detail(False,3,1,28)
  self.assertIn('28',detail);self.assertIn('可能不完整',detail);self.assertIn('2',detail);self.assertNotIn('key',detail)

 def test_partial_manifest_runs_catalog_and_selected_history_without_other_chat_body(self):
  from unittest.mock import patch
  from tempfile import TemporaryDirectory
  contact=sqlite3.connect(':memory:');self.addCleanup(contact.close)
  contact.execute('CREATE TABLE contact(username TEXT,nick_name TEXT,remark TEXT)')
  contact.executemany('INSERT INTO contact VALUES(?,?,?)',[('friend','朋友',''),('other','另一个会话','')])
  messages,table=self.connection('friend');self.add(messages,table,1,1,100,1,'所选正文')
  other=reader.message_table('other');messages.execute(f'CREATE TABLE [{other}](private_body TEXT)');messages.execute(f'INSERT INTO [{other}] VALUES(?)',('不能读取的其他正文',))
  class Open:
   def __init__(self,conn):self.conn=conn
   def __enter__(self):return self.conn
   def __exit__(self,*args):pass
  def opener(root,keys,rel,temp):
   if rel=='contact/contact.db':return Open(contact)
   if rel=='message/message_0.db':return Open(messages)
   raise ValueError('unavailable fixture key')
  with TemporaryDirectory() as directory,patch.object(reader,'_open_copy',side_effect=opener):
   root=Path(directory);(root/'message').mkdir()
   for i in range(3):(root/'message'/f'message_{i}.db').touch()
   catalog=reader._read_selected(root,{},root,'list',None)
   self.assertEqual('ok',catalog['status']);self.assertEqual(2,catalog['total']);self.assertEqual('local-readable-message-metadata',catalog['scope']);self.assertIn('1/3',catalog['detail']);self.assertIn('2个消息分库暂不可读',catalog['detail']);self.assertNotIn('不能读取',str(catalog))
   history=reader._read_selected(root,{},root,'history','friend')
   self.assertEqual('ok',history['status']);self.assertEqual(['所选正文'],[m['text'] for m in history['messages']]);self.assertIn('1/3',history['detail']);self.assertFalse(history['identityKnown'])

 def test_nonpositive_server_ids_use_unique_local_fallback(self):
  conn,table=self.connection('friend');self.add(conn,table,1,-1,100,1,'待发送一');self.add(conn,table,2,-1,101,1,'待发送二')
  rows,_=reader.chat_messages(conn,'friend',{},'account','message_0.db')
  self.assertNotEqual(rows[0]['id'],rows[1]['id'])

if __name__=='__main__':unittest.main()
