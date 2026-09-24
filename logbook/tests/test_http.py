import http.client
from pathlib import Path
import sys
import tempfile
import threading
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.server import Application,create_server
from test_archive import bundle,TEXT

class HttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        web=self.root/'web';web.mkdir();(web/'logbook.html').write_text('<h1>Log</h1>')
        self.app=Application(self.root/'data',web,watch=False)
        self.server=create_server(self.app,0);self.port=self.server.server_port
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.app.close();self.tmp.cleanup()
    def request(self,method,path,body=None,headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.port);c.request(method,path,body,headers or {})
        r=c.getresponse();status=r.status;hdr=dict(r.getheaders());data=r.read();c.close();return status,hdr,data
    def test_no_token_cannot_read_messages_or_import(self):
        self.assertEqual(self.request('GET','/v1/state')[0],401)
        self.assertEqual(self.request('POST','/v1/import',bundle(TEXT),{'Content-Type':'application/zip','X-Logbook-Target':'target'})[0],401)
    def test_native_import_reads_same_structured_data(self):
        import json
        h={'X-Logbook-Token':self.app.token,'Content-Type':'application/zip','X-Logbook-Target':'target'}
        code,_,data=self.request('POST','/v1/import',bundle(TEXT),h);self.assertEqual(code,200);self.assertEqual(json.loads(data)['added'],2)
        code,_,data=self.request('GET','/v1/state',headers={'X-Logbook-Token':self.app.token})
        self.assertEqual(code,200);self.assertEqual(json.loads(data)['group']['messageCount'],2)
    def test_cookie_import_requires_same_origin_and_target(self):
        code,h,_=self.request('GET','/');self.assertEqual(code,200)
        headers={'Cookie':h['Set-Cookie'].split(';')[0],'Content-Type':'application/zip','X-Logbook-Request':'1','X-Logbook-Target':'target','Origin':'https://elsewhere.example'}
        self.assertEqual(self.request('POST','/v1/import',bundle(TEXT),headers)[0],403)
        headers['Origin']='http://127.0.0.1:'+str(self.port)
        self.assertEqual(self.request('POST','/v1/import',bundle(TEXT),headers)[0],200)
        del headers['X-Logbook-Target']
        self.assertEqual(self.request('POST','/v1/import',bundle(TEXT),headers)[0],400)
    def test_host_and_static_path_guard(self):
        self.assertEqual(self.request('GET','/health',headers={'Host':'evil.example'})[0],403)
        self.assertEqual(self.request('GET','/../data/service.token')[0],404)
