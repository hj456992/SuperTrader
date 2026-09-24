"""Launcher contract tests; every credential and artifact here is synthetic."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2]


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy(SOURCE / 'run.py', self.root / 'run.py')
        self.base = self.root / 'dsh'
        for relative in ('app-boot/target/dsh-java.jar',
                         'plugins/model-registry/target/model-registry.jar',
                         'plugins/model-deepseek/target/model-deepseek.jar'):
            self.touch(self.base / relative)
        for plugin in ('session', 'session-projection', 'agent', 'context', 'tools', 'agent-loop'):
            self.touch(self.base / f'plugins/{plugin}/target/{plugin}.jar')
        for relative in ('target/garden-demo.jar', 'web/dist/index.html',
                         'plugins/feishu-history/target/feishu-history-plugin.jar',
                         'feishu/collector.py'):
            self.touch(self.root / relative)
        self.java = self.root / 'java'
        self.java.write_text('#!/bin/sh\nif [ "$1" = "-version" ]; then\n'
                             'echo \'openjdk version "17.0.20"\' >&2\nelse\n'
                             'touch java-started\nfi\n')
        self.java.chmod(0o700)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        self.env = {'PATH': os.environ['PATH'], 'DSH_JAVA_HOME': str(self.base),
                    'JAVA_BIN': str(self.java), 'GARDEN_PORT': str(port),
                    'DEEPSEEK_API_KEY': 'fake-model-secret',
                    'GARDEN_DB_URL': 'jdbc:postgresql://127.0.0.1:15440/test_only',
                    'GARDEN_DB_USER': 'fake-user', 'GARDEN_DB_PASSWORD': 'fake-db-secret'}

    def touch(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()

    def run_launcher(self, *args):
        result = subprocess.run([sys.executable, str(self.root / 'run.py'), *args],
                                env=self.env, cwd=self.root, capture_output=True, text=True)
        for value in ('fake-model-secret', 'fake-db-secret', 'fake-user'):
            self.assertNotIn(value, result.stdout + result.stderr)
        self.assertNotIn('Traceback', result.stderr)
        return result

    def test_check_has_no_launch_or_disk_side_effect(self):
        result = self.run_launcher('--check')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / 'java-started').exists())
        self.assertFalse((self.root / '.runtime').exists())

    def test_missing_host_artifact_fails_before_launch(self):
        (self.base / 'app-boot/target/dsh-java.jar').unlink()
        result = self.run_launcher('--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('dsh-java.jar', result.stderr)
        self.assertFalse((self.root / 'java-started').exists())

    def test_partial_database_config_does_not_use_private_fallback(self):
        del self.env['GARDEN_DB_PASSWORD']
        result = self.run_launcher('--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('GARDEN_DB_PASSWORD', result.stderr)

    def test_busy_port_fails_without_stopping_listener(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            sock.listen()
            self.env['GARDEN_PORT'] = str(sock.getsockname()[1])
            result = self.run_launcher('--check')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('GARDEN_PORT', result.stderr)
            self.assertGreater(sock.fileno(), 0)
        self.assertFalse((self.root / 'java-started').exists())

    def test_invalid_port_never_echoes_value(self):
        self.env['GARDEN_PORT'] = 'fake-model-secret'
        result = self.run_launcher('--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('GARDEN_PORT', result.stderr)

    def test_launch_writes_private_bootstrap_without_credentials(self):
        result = self.run_launcher()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'java-started').exists())
        bootstrap = self.root / '.runtime/bootstrap.yml'
        self.assertEqual(bootstrap.stat().st_mode & 0o777, 0o600)
        content = bootstrap.read_text()
        for value in ('fake-model-secret', 'fake-db-secret', 'fake-user', 'jdbc:postgresql'):
            self.assertNotIn(value, content)

    def test_missing_agent_loop_is_a_preflight_failure(self):
        (self.base / 'plugins/agent-loop/target/agent-loop.jar').unlink()
        result = self.run_launcher('--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('agent-loop', result.stderr)

    def test_bootstrap_wires_backend_required_services(self):
        result = self.run_launcher()
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = {}
        for line in (self.root / '.runtime/bootstrap.yml').read_text().splitlines()[1:]:
            key, value = line.strip().removeprefix('- ').split(': ', 1)
            if key == 'id':
                row = rows.setdefault(json.loads(value), {})
            else:
                row[key] = json.loads(value)
        self.assertEqual(set(rows), {'model-registry', 'model-deepseek', 'feishu-history',
                                    'garden-demo', 'sessions', 'session-projections',
                                    'agents', 'prompt-context', 'tools', 'agent-loop'})
        self.assertEqual(rows['agent-loop']['inject'],
                         ['agents', 'sessions', 'sessionProjections', 'systemPrompt', 'tools', 'toolScheduler', 'llmRuntime'])
        self.assertTrue({'agents', 'systemPrompt', 'tools'} <= set(rows['garden-demo']['inject']))
        self.assertEqual(rows['sessions']['inject'], ['sessionProjections'])
        self.assertEqual(rows['tools']['inject'], ['systemPrompt'])

    def test_unsupported_java_fails_before_bootstrap(self):
        self.java.write_text('#!/bin/sh\necho \'openjdk version "11.0.1"\' >&2\n')
        result = self.run_launcher('--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Java 17', result.stderr)
        self.assertFalse((self.root / '.runtime').exists())


if __name__ == '__main__':
    unittest.main()
