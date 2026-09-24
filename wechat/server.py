#!/usr/bin/env python3
"""Loopback extension for 爱聊; the running Java app and its data remain intact."""
import argparse
import http.client
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import secrets
import signal
import sys
import threading
from urllib.parse import unquote, urlsplit

from collector import Collector
from store import CaptureStore

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[1]
PERSON_ACTIONS = {'create', 'append', 'correct', 'remove-message', 'adopt', 'delete', 'analyze', 'cancel'}


class CaptureApplication:
    def __init__(self, database, helper, web, upstream_port=48740):
        self.store = CaptureStore(database)
        self.collector = Collector(self.store, helper)
        self.web = Path(web).resolve()
        self.session = secrets.token_urlsafe(32)
        self.upstream_port = upstream_port
        self.upstream_cookie = None
        self.proxy_lock = threading.Lock()

    def state(self):
        result = self.store.state()
        result['collector'] = self.collector.state()
        return result

    def proxy(self, method, path, body=None):
        with self.proxy_lock:
            for attempt in range(2):
                if self.upstream_cookie is None:
                    connection = http.client.HTTPConnection('127.0.0.1', self.upstream_port, timeout=8)
                    connection.request('GET', '/')
                    response = connection.getresponse()
                    cookies = SimpleCookie()
                    cookies.load(response.getheader('Set-Cookie', ''))
                    response.read()
                    connection.close()
                    if 'garden_session' not in cookies:
                        raise RuntimeError('Original app session unavailable')
                    self.upstream_cookie = 'garden_session=' + cookies['garden_session'].value
                headers = {'Cookie': self.upstream_cookie}
                if method == 'POST':
                    headers.update({'Origin': 'http://127.0.0.1:' + str(self.upstream_port),
                                    'Content-Type': 'application/json', 'X-Garden-Request': '1'})
                connection = http.client.HTTPConnection('127.0.0.1', self.upstream_port, timeout=15)
                connection.request(method, path, body=body, headers=headers)
                response = connection.getresponse()
                status, data = response.status, response.read()
                connection.close()
                if status != 401:
                    return status, data
                self.upstream_cookie = None
            return status, data

    def close(self):
        self.collector.close()
        self.store.close()


class LogReadApplication(CaptureApplication):
    """Person API proxy plus read-only independent structured-log client."""
    log_source = True

    def __init__(self, web, token_file, upstream_port=48740, log_port=48742):
        sys.path.insert(0, str(ROOT))
        from logbook.client import LogClient
        self.log = LogClient(token_file, log_port)
        self.web = Path(web).resolve()
        self.session = secrets.token_urlsafe(32)
        self.upstream_port = upstream_port
        self.upstream_cookie = None
        self.proxy_lock = threading.Lock()

    def state(self):
        return self.log.state()

    def close(self):
        pass


def create_server(app, port=48741):
    class Handler(BaseHTTPRequestHandler):
        server_version = 'AiChatLocal/1'

        def log_message(self, *_):
            pass  # Never log request paths, chat bodies, cookies, or credentials.

        def send(self, status, body, content_type='application/json; charset=utf-8', cookie=False, disposition=None):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            if disposition:
                self.send_header('Content-Disposition', disposition)
            if cookie:
                self.send_header('Set-Cookie', 'aichat_session=' + app.session + '; HttpOnly; SameSite=Strict; Path=/')
            self.end_headers()
            self.wfile.write(body)

        def json(self, status, value):
            self.send(status, json.dumps(value, ensure_ascii=False, allow_nan=False).encode('utf-8'))

        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch()

        def dispatch(self):
            try:
                self._dispatch()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                self.json(500, {'error': '本地操作未完成，请稍后重试。'})

        def _dispatch(self):
            host = '127.0.0.1:' + str(self.server.server_port)
            if self.headers.get('Host') != host:
                self.json(403, {'error': '仅限本地访问。'})
                return
            path = unquote(urlsplit(self.path).path)
            if path == '/health' and self.command == 'GET' and getattr(app, 'log_source', False):
                self.json(200, {'app': 'AiChatViewer', 'version': 1, 'mode': 'structured-log-readonly', 'logSources': ['wechat_export','wechat_database','wechat_cli_history']})
                return
            if path.startswith('/api/'):
                cookies = SimpleCookie()
                try:
                    cookies.load(self.headers.get('Cookie', ''))
                except Exception:
                    pass
                token = cookies.get('aichat_session')
                if token is None or not secrets.compare_digest(token.value, app.session):
                    self.json(401, {'error': '请先打开爱聊首页。'})
                    return
                if self.command == 'GET':
                    if path == '/api/wechat/state':
                        self.json(200, app.state())
                    elif path.startswith('/api/wechat/attachment/') and getattr(app, 'log_source', False):
                        try:
                            status, headers, data = app.log.get(path.replace('/api/wechat/attachment/', '/v1/attachment/', 1))
                            self.send(status, data, 'application/octet-stream', disposition=headers.get('Content-Disposition'))
                        except Exception:
                            self.json(502, {'error': '聊天日志附件暂时不可用。'})
                    elif path == '/api/state':
                        self.proxy('GET', path)
                    else:
                        self.json(404, {'error': '接口不存在。'})
                    return
                if (self.headers.get('Origin') != 'http://' + host
                        or self.headers.get('X-Garden-Request') != '1'
                        or self.headers.get('Content-Type', '').split(';')[0] != 'application/json'
                        or self.headers.get('Transfer-Encoding')):
                    self.json(403, {'error': '请求来源无效。'})
                    return
                try:
                    size = int(self.headers.get('Content-Length', '-1'))
                    if not 0 <= size <= 100000:
                        self.json(413, {'error': '请求内容太大或缺少长度。'})
                        return
                    self.connection.settimeout(5)
                    body = self.rfile.read(size)
                    value = json.loads(body)
                    if not isinstance(value, dict):
                        raise ValueError()
                except (ValueError, UnicodeError):
                    self.json(400, {'error': '请输入有效 JSON 对象。'})
                    return
                if path in ('/api/wechat/start', '/api/wechat/pause') and getattr(app, 'log_source', False):
                    self.json(410, {'error': '爱聊现从独立聊天日志读取；请在聊天日志应用导入微信原生消息包。'})
                elif path == '/api/wechat/start':
                    app.collector.start()
                    self.json(200, app.state())
                elif path == '/api/wechat/pause':
                    app.collector.pause()
                    self.json(200, app.state())
                elif path.removeprefix('/api/') in PERSON_ACTIONS:
                    self.proxy('POST', path, body)
                else:
                    self.json(404, {'error': '接口不存在。'})
                return
            if self.command != 'GET':
                self.json(405, {'error': '不支持的请求。'})
                return
            relative = 'index.html' if path == '/' else path.lstrip('/')
            file = (app.web / relative).resolve()
            if not file.is_relative_to(app.web) or not file.is_file():
                self.json(404, {'error': '页面不存在。'})
                return
            mime = mimetypes.guess_type(file.name)[0] or 'application/octet-stream'
            self.send(200, file.read_bytes(), mime + ('; charset=utf-8' if mime.startswith('text/') else ''),
                      cookie=file.suffix == '.html')

        def proxy(self, method, path, body=None):
            try:
                status, data = app.proxy(method, path, body)
                self.send(status, data)
            except Exception:
                self.json(502, {'error': '原有人物服务暂时不可用；微信采集和已保存群消息仍可使用。'})

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description='爱聊本地微信采集服务')
    parser.add_argument('--port', type=int, default=48741)
    parser.add_argument('--upstream-port', type=int, default=48740)
    parser.add_argument('--database', type=Path, default=WORKSPACE / 'work/wechat-capture/capture.sqlite3')
    parser.add_argument('--helper', type=Path, default=ROOT / '.runtime/wechat-capture-native')
    parser.add_argument('--web', type=Path, default=ROOT / 'web/dist')
    parser.add_argument('--start', action='store_true', help='启动后开启已指定群的采集')
    args = parser.parse_args()
    os.umask(0o077)
    app = LogReadApplication(args.web, WORKSPACE / 'work/chatlog/service.token', args.upstream_port)
    server = create_server(app, args.port)
    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print('爱聊聊天日志：http://127.0.0.1:' + str(server.server_port) + '/wechat.html', flush=True)
    try:
        server.serve_forever(poll_interval=0.3)
    finally:
        server.server_close()
        app.close()


if __name__ == '__main__':
    main()
