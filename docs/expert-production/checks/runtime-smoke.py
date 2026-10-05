#!/usr/bin/env python3
"""Read-only route smoke probe; never certifies a complete production workflow."""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:48760')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
base = args.base_url.rstrip('/')
url = urllib.parse.urlparse(base)
if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', '::1') or url.username or url.password or url.path or url.query or url.fragment:
    parser.error('Use a loopback HTTP origin without credentials or path.')
# Local services must not be routed through an inherited HTTP proxy.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def probe(path):
    try:
        response = opener.open(base + path, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    except (urllib.error.URLError, TimeoutError):
        return {'path': path, 'httpStatus': None, 'result': 'unreachable'}
    with response:
        status = response.status
        try:
            data = json.loads(response.read(65536))
        except (ValueError, UnicodeError):
            data = {}
    if not isinstance(data, dict):
        data = {}
    # Do not persist arbitrary response bodies, book text, IDs or credentials.
    result = 'response_received'
    if data.get('error') == '接口不存在':
        result = 'route_not_implemented'
    elif path == '/api/health' and status == 200 and data.get('status') == 'ready':
        result = 'legacy_service_ready'
    return {'path': path, 'httpStatus': status, 'result': result}

checks = [probe('/api/health'), probe('/api/expert-production/v1/builds/00000000-0000-4000-8000-000000000001/snapshot')]
blocked = any(item['result'] in ('route_not_implemented', 'unreachable') for item in checks)
report = {
    'scope': 'read_only_route_probe',
    'status': 'blocked' if blocked else 'inconclusive',
    'checks': checks,
    'fullWorkflowPassed': False,
    'limitation': 'A nonexistent build probes the missing-route response only. Other responses require a real isolated build and full workflow tests; they do not count as a pass.',
}
payload = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
if args.output:
    args.output.write_text(payload, encoding='utf-8')
print(payload, end='')
sys.exit(1 if blocked else 2)
