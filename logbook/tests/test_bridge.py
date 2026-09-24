import http.client,json
from pathlib import Path
import sys,tempfile,threading,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'wechat'))
from server import LogReadApplication,create_server

class BridgeTests(unittest.TestCase):
    def test_product_mode_is_structured_read_only_even_without_logger(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'wechat.html').write_text('viewer')
            app=LogReadApplication(root,root/'missing-token',log_port=1)
            server=create_server(app,0);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
            def request(method,path,body=None,headers=None):
                c=http.client.HTTPConnection('127.0.0.1',port);c.request(method,path,body,headers or {});r=c.getresponse();status=r.status;h=dict(r.getheaders());data=r.read();c.close();return status,h,data
            try:
                code,_,body=request('GET','/health');self.assertEqual(code,200);self.assertEqual(json.loads(body)['mode'],'structured-log-readonly')
                _,headers,_=request('GET','/wechat.html');cookie=headers['Set-Cookie'].split(';')[0]
                code,_,body=request('GET','/api/wechat/state',headers={'Cookie':cookie})
                self.assertEqual(code,200);self.assertEqual(json.loads(body)['source']['status'],'offline')
                for action in ['start','pause']:
                    code,_,_=request('POST','/api/wechat/'+action,b'{}',{'Cookie':cookie,'Origin':f'http://127.0.0.1:{port}','Content-Type':'application/json','X-Garden-Request':'1'})
                    self.assertEqual(code,410)
            finally:server.shutdown();server.server_close();app.close()
