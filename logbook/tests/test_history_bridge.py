import json,tempfile,threading,unittest,http.client
from pathlib import Path
from logbook.server import Application,create_server

class HistoryBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/'source-mode.json').write_text('{"source":"wechat_cli_history"}')
        self.app=Application(self.root,self.root,watch=False);self.server=create_server(self.app,0)
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.addCleanup(self.app.close);self.addCleanup(self.server.server_close);self.addCleanup(self.server.shutdown)
    def request(self,path,value=None,token=True,cookie=False):
        headers={'Content-Type':'application/json'}
        if token:headers['X-Logbook-Token']=self.app.token
        if cookie:headers['Cookie']='logbook_session='+self.app.session
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        c.request('GET' if value is None else 'POST',path,None if value is None else json.dumps(value),headers)
        r=c.getresponse();body=json.loads(r.read());c.close();return r.status,body
    def batch(self,n=1):
        return dict(schema='aichat.history.v1',target='卧底不追高',account_fingerprint='a'*64,chat_id='room@chatroom',rows=[dict(database='message_0.db',local_id=n,server_id=str(100+n),sender_id='fixture',author='测试',text='原文',create_time=1700000000,local_type=1)],checkpoint=dict(account='a'*64,chat='room@chatroom',cursor={'message_0.db':n},salts={'message_0.db':'b'*32}),has_more=False)
    def test_native_ingest_roundtrip_replay_and_checkpoint(self):
        b=self.batch();self.assertEqual(self.request('/v1/history-batch',b)[0],200)
        self.assertEqual(self.request('/v1/history-batch',b)[1]['added'],0)
        self.assertEqual(self.request('/v1/history-checkpoint')[1]['checkpoint'],b['checkpoint'])
        state=self.request('/v1/state')[1];self.assertEqual(state['source']['type'],'wechat_cli_history');self.assertEqual(state['group']['messageCount'],1)
        self.assertEqual(state['source']['status'],'connected')
    def test_wrong_source_and_regression_never_advance(self):
        self.assertEqual(self.request('/v1/history-batch',self.batch(2))[0],200)
        wrong=self.batch(3);wrong['target']='其他群';self.assertEqual(self.request('/v1/history-batch',wrong)[0],422)
        self.assertEqual(self.request('/v1/history-batch',self.batch(1))[0],422)
        self.assertEqual(self.request('/v1/history-checkpoint')[1]['checkpoint']['cursor']['message_0.db'],2)
    def test_browser_cannot_write_history_and_error_detail_is_sanitized(self):
        self.assertEqual(self.request('/v1/history-batch',self.batch(),token=False,cookie=True)[0],403)
        self.assertEqual(self.request('/v1/history-checkpoint',token=False)[0],401)
        self.request('/v1/history-status',dict(status='error',detail='PRIVATE_KEY_DO_NOT_FORWARD'))
        s=self.request('/v1/state')[1];self.assertNotIn('PRIVATE_KEY',json.dumps(s))
