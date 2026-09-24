"""Synthetic encrypted DB fixture kept alive for Java host integration.

First line: JSON config/key/database paths. stdin lines: append or quit.
An append commits a new WAL message; no personal data is used.
"""
import json
import sys
from test_structured import StructuredTests

fixture = StructuredTests()
fixture.setUp()
try:
    print(json.dumps({'cliConfig': str(fixture.config), 'keyFile': str(fixture.keyfile), 'dbRoot': str(fixture.dbdir), 'dataRoot': str(fixture.root), 'cursorFile': str(fixture.cursor), 'initialCount': 3}), flush=True)
    next_id = 18
    for command in sys.stdin:
        if command.strip() == 'quit': break
        if command.strip() == 'append':
            fixture.insert(fixture.db, next_id, '人工集成测试消息', 1700000100 + next_id)
            print(json.dumps({'appended': next_id}), flush=True)
            next_id += 1
        else:
            print(json.dumps({'error': 'expected append or quit'}), flush=True)
finally:
    fixture.tearDown()
