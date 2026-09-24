from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import socket,sys,tempfile,threading,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.launch import ready,ensure_service

class LaunchTests(unittest.TestCase):
    def test_legacy_html_success_is_not_new_viewer_readiness(self):
        class Legacy(BaseHTTPRequestHandler):
            server_version='AiChatLocal/1'
            def log_message(self,*args):pass
            def do_GET(self):self.send_response(200);self.end_headers();self.wfile.write(b'<html>old OCR</html>')
        s=ThreadingHTTPServer(('127.0.0.1',0),Legacy);threading.Thread(target=s.serve_forever,daemon=True).start()
        try:self.assertFalse(ready(s.server_port,'AiChatViewer'))
        finally:s.shutdown();s.server_close()
    def test_child_exit_is_failure_not_false_success(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);script=p/'fails.py';script.write_text('raise SystemExit(7)\n')
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            with self.assertRaisesRegex(RuntimeError,'退出|启动'):
                ensure_service(port,'AiChatLog',script,p/'service.pid',p/'service.log',timeout=2)
