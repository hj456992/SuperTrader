#!/usr/bin/env python3
"""Local structured-message owner with an optional read-only WeChat DB source."""
import argparse
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import secrets
import signal
import sys
import threading
from urllib.parse import unquote,urlsplit,quote
if __package__ in (None,''):
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]));__package__='logbook'
from .archive import ArchiveError,MAX_ARCHIVE,TARGET
from .store import LogStore,private_write
from .watcher import InboxWatcher
from .database_worker import DatabaseWorker
from .history_bridge import HistoryBridge

ROOT=Path(__file__).resolve().parents[1]
WORKSPACE=ROOT.parents[1]

class Application:
    def __init__(self,data,web,watch=True):
        self.store=LogStore(data);self.web=Path(web).resolve();self.session=secrets.token_urlsafe(32)
        token=self.store.root/'service.token'
        if not token.exists():private_write(token,secrets.token_urlsafe(32).encode())
        token.chmod(0o600);self.token=token.read_text().strip()
        if len(self.token)<32:raise ValueError('Invalid local service token')
        self.watcher=InboxWatcher(self.store) if watch else None
        self.history=HistoryBridge(self.store)
        mode=self.store.root/'source-mode.json'
        self.history_mode=mode.is_file() and json.loads(mode.read_text()).get('source')=='wechat_cli_history'
        self.database=DatabaseWorker(self.store) if watch and not self.history_mode else None
    def state(self):
        s=self.store.state();s['inbox']=self.watcher.state() if self.watcher else dict(status='disabled',error=None)
        if self.database:
            s['source'].update(self.database.state())
            s['coverage']['description']='展示最近 2000 条已保存的数据库消息，筛选和搜索限当前展示范围，总数包含全部已保存记录；只收录此电脑已有的目标群记录，手机未同步内容不包含在内。非文本暂显示类型。历史导出包仍按导入时指定群归属。' + (' 当前首次连接尚未完成。' if s['source']['status']=='setup_required' else '')
        if self.history_mode:
            s['source'].update(self.history.state())
            for segment in s['segments']:
                if segment.get('source')=='wechat_database':
                    segment['source']='wechat_cli_history';segment['filename']='history 自动同步'
                    for message in segment['messages']:message['source']='wechat_cli_history'
            s['coverage']['description']='通过 dsh-java 插件调用 wechat-cli history，只同步本机已有的目标群记录；展示与搜索限最近 2000 条数据库消息。非文本暂显示类型；历史导出包保留原归属说明。'
        return s
    def close(self):
        if self.watcher:self.watcher.close()
        if self.database and not self.database.close():return
        self.store.close()


def create_server(app,port=48742):
    class Handler(BaseHTTPRequestHandler):
        server_version='AiChatLog/1'
        def log_message(self,*args):pass
        def send(self,status,body,mime='application/json; charset=utf-8',cookie=False,download=None):
            self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Referrer-Policy','no-referrer')
            self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            if cookie:self.send_header('Set-Cookie','logbook_session='+app.session+'; HttpOnly; SameSite=Strict; Path=/')
            if download:self.send_header('Content-Disposition',"attachment; filename*=UTF-8''"+quote(download,safe=''))
            self.end_headers();self.wfile.write(body)
        def json(self,status,value):self.send(status,json.dumps(value,ensure_ascii=False,allow_nan=False).encode())
        def do_GET(self):self.dispatch()
        def do_POST(self):self.dispatch()
        def dispatch(self):
            try:self.handle_request()
            except (BrokenPipeError,ConnectionResetError):pass
            except Exception:self.json(500,dict(error='日志服务暂时未完成操作。'))
        def handle_request(self):
            host='127.0.0.1:'+str(self.server.server_port)
            if self.headers.get('Host')!=host:self.json(403,dict(error='仅限本地访问。'));return
            path=unquote(urlsplit(self.path).path)
            if path=='/health' and self.command=='GET':self.json(200,dict(app='AiChatLog',version=1,acquisition='history-plugin-and-export'));return
            if path.startswith('/v1/'):
                token=self.headers.get('X-Logbook-Token','')
                native=bool(token) and secrets.compare_digest(token,app.token)
                cookie=SimpleCookie()
                try:cookie.load(self.headers.get('Cookie',''))
                except Exception:pass
                session=cookie.get('logbook_session')
                browser=session is not None and secrets.compare_digest(session.value,app.session)
                if not(native or browser):self.json(401,dict(error='请先打开聊天日志应用。'));return
                origin=self.headers.get('Origin')
                if origin and origin!='http://'+host:self.json(403,dict(error='请求来源无效。'));return
                if path.startswith('/v1/history-'):
                    if not native:self.json(403,dict(error='此入口仅供本地采集插件使用。'));return
                    if not app.history_mode:self.json(409,dict(error='尚未启用 history 插件来源。'));return
                    try:
                        if path=='/v1/history-checkpoint' and self.command=='GET':self.json(200,app.history.checkpoint());return
                        if self.command!='POST' or path not in ('/v1/history-batch','/v1/history-status'):
                            self.json(404,dict(error='接口不存在。'));return
                        if self.headers.get('Content-Type','').split(';')[0]!='application/json' or self.headers.get('Transfer-Encoding'):
                            self.json(400,dict(error='需要 JSON。'));return
                        size=int(self.headers.get('Content-Length','-1'))
                        if not 0<size<=8*1024*1024:self.json(413,dict(error='批次超过限制。'));return
                        self.connection.settimeout(30);body=self.rfile.read(size)
                        if len(body)!=size:raise ValueError('批次未完整收到。')
                        value=json.loads(body)
                        if not isinstance(value,dict):raise ValueError('批次格式无效。')
                        if path=='/v1/history-batch':result=app.history.ingest(value)
                        else:app.history.update(value);result=dict(ok=True)
                        self.json(200,result);return
                    except (ValueError,KeyError,TypeError):self.json(422,dict(error='history 批次、来源或检查点校验失败。'));return
                if self.command=='GET':
                    if path=='/v1/state':self.json(200,app.state());return
                    if path.startswith('/v1/attachment/'):
                        try:file,name=app.store.attachment(path.removeprefix('/v1/attachment/'))
                        except KeyError:self.json(404,dict(error='附件不存在。'));return
                        self.send(200,file.read_bytes(),'application/octet-stream',download=name);return
                    self.json(404,dict(error='接口不存在。'));return
                if path!='/v1/import':self.json(404,dict(error='接口不存在。'));return
                if not native and (origin!='http://'+host or self.headers.get('X-Logbook-Request')!='1'):
                    self.json(403,dict(error='请求来源无效。'));return
                if self.headers.get('X-Logbook-Target')!='target':self.json(400,dict(error='需要明确导入到卧底不追高日志。'));return
                if self.headers.get('Content-Type','').split(';')[0]!='application/zip' or self.headers.get('Transfer-Encoding'):
                    self.json(400,dict(error='请提供微信原生 ZIP 消息包。'));return
                try:size=int(self.headers.get('Content-Length','-1'))
                except ValueError:size=-1
                if not 0<size<=MAX_ARCHIVE:self.json(413,dict(error='消息包为空或超过 64 MB。'));return
                self.connection.settimeout(30);data=self.rfile.read(size)
                if len(data)!=size:self.json(400,dict(error='消息包尚未完整收到。'));return
                name=unquote(self.headers.get('X-Logbook-Filename','微信导出.zip'))
                try:result=app.store.import_archive(data,name,TARGET)
                except ArchiveError as error:self.json(422,dict(error=str(error)));return
                self.json(200,result);return
            if self.command!='GET':self.json(405,dict(error='请求方式不支持。'));return
            relative='logbook.html' if path=='/' else path.lstrip('/')
            if relative not in ('logbook.html','logview.js','logview.css'):
                self.json(404,dict(error='页面不存在。'));return
            file=(app.web/relative).resolve()
            if not file.is_relative_to(app.web) or not file.is_file():self.json(404,dict(error='页面不存在。'));return
            self.send(200,file.read_bytes(),(mimetypes.guess_type(file.name)[0] or 'text/plain')+'; charset=utf-8',cookie=file.suffix=='.html')
    return ThreadingHTTPServer(('127.0.0.1',port),Handler)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=48742);parser.add_argument('--data',type=Path,default=WORKSPACE/'work/chatlog');parser.add_argument('--web',type=Path,default=ROOT/'web/dist');args=parser.parse_args()
    os.umask(0o077);app=Application(args.data,args.web);server=create_server(app,args.port)
    def stop(*args):threading.Thread(target=server.shutdown,daemon=True).start()
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    try:server.serve_forever(poll_interval=0.3)
    finally:server.server_close();app.close()
if __name__=='__main__':main()
