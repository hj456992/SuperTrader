"""Read-only client used by 爱聊. The logger owns all import/storage writes."""
import http.client
import json
from pathlib import Path
from .archive import TARGET

class LogClient:
    def __init__(self,token_file,port=48742):self.token_file=Path(token_file);self.port=port
    def get(self,path):
        if path!='/v1/state' and not path.startswith('/v1/attachment/'):
            raise ValueError('Unsupported read path')
        token=self.token_file.read_text().strip()
        c=http.client.HTTPConnection('127.0.0.1',self.port,timeout=5)
        try:
            c.request('GET',path,headers={'X-Logbook-Token':token})
            r=c.getresponse();body=r.read(128*1024*1024+1)
            if len(body)>128*1024*1024:raise ValueError('Response too large')
            return r.status,dict(r.getheaders()),body
        finally:c.close()
    def state(self):
        try:
            status,_,body=self.get('/v1/state')
            if status!=200:raise ValueError('Logger unavailable')
            result=json.loads(body)
            if result['source']['type'] not in ('wechat_export','wechat_database','wechat_cli_history') or result['group']['name']!=TARGET:
                raise ValueError('Unexpected log source')
            return result
        except (OSError,ValueError,KeyError,http.client.HTTPException):
            return dict(group=dict(name=TARGET,messageCount=0,memberCount=0,identity='assigned_at_import'),members=[],segments=[],stats=dict(observations=0,duplicates=0),source=dict(type='wechat_export',name='聊天日志',status='offline',lastImportAt=None),coverage=dict(complete=False,description='聊天日志应用尚未连接。已有日志仍保存在日志应用中；连接恢复后会重新显示。'))
