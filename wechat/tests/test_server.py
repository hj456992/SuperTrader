import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from server import CaptureApplication, create_server
except ImportError:
    CaptureApplication = create_server = None


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(create_server, 'Capture HTTP service has not been implemented')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        web = root / 'web'
        web.mkdir()
        (web / 'index.html').write_text('<html>test app</html>')
        self.app = CaptureApplication(root / 'data' / 'capture.sqlite3', root / 'missing-helper', web)
        self.http = create_server(self.app, 0)
        self.host = '127.0.0.1:' + str(self.http.server_port)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.finish)

    def finish(self):
        self.http.shutdown()
        self.http.server_close()
        self.app.close()

    def request(self, path, method='GET', data=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.http.server_port)
        connection.request(method, path, body=data, headers=headers or {})
        response = connection.getresponse()
        status, response_headers, body = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return status, response_headers, body

    def login(self):
        status, headers, _ = self.request('/')
        self.assertEqual(status, 200)
        self.assertIn('HttpOnly', headers['Set-Cookie'])
        self.assertIn('SameSite=Strict', headers['Set-Cookie'])
        return headers['Set-Cookie'].split(';')[0]

    def test_api_requires_local_session_and_host(self):
        self.assertEqual(self.request('/api/wechat/state')[0], 401)
        cookie = self.login()
        self.assertEqual(self.request('/api/wechat/state', headers={'Cookie': cookie, 'Host': 'evil.example'})[0], 403)
        status, _, body = self.request('/api/wechat/state', headers={'Cookie': cookie})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['group']['messageCount'], 0)

    def test_cross_origin_cannot_start_collector(self):
        cookie = self.login()
        headers = {'Cookie': cookie, 'Origin': 'http://evil.example',
                   'Content-Type': 'application/json', 'X-Garden-Request': '1'}
        self.assertEqual(self.request('/api/wechat/start', 'POST', '{}', headers)[0], 403)
        self.assertFalse(self.app.collector.state()['enabled'])

    def test_pause_start_endpoints_control_actual_collector(self):
        cookie = self.login()
        headers = {'Cookie': cookie, 'Origin': 'http://' + self.host,
                   'Content-Type': 'application/json', 'X-Garden-Request': '1'}
        status, _, body = self.request('/api/wechat/start', 'POST', '{}', headers)
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)['collector']['enabled'])
        status, _, body = self.request('/api/wechat/pause', 'POST', '{}', headers)
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)['collector']['enabled'])

    def test_parent_paths_and_invalid_json_are_rejected(self):
        self.assertEqual(self.request('/../secret')[0], 404)
        cookie = self.login()
        headers = {'Cookie': cookie, 'Origin': 'http://' + self.host,
                   'Content-Type': 'application/json', 'X-Garden-Request': '1'}
        self.assertEqual(self.request('/api/wechat/start', 'POST', '[]', headers)[0], 400)


if __name__ == '__main__':
    unittest.main()
