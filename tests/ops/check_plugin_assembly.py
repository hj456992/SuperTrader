"""Explicit integration probe: real DSH plugins, no business DB or model request.

Run separately from unittest discovery after build.sh. Only its own child is stopped.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import run as launcher


def main():
    base = Path(os.environ.get('DSH_JAVA_HOME', launcher.DEFAULT_BASE)).resolve()
    rows = [row for row in launcher.plugin_rows(base) if row['id'] != 'garden-demo']
    allowed = {'model-registry', 'model-deepseek', 'feishu-history', 'sessions',
               'session-projections', 'agents', 'prompt-context', 'tools', 'agent-loop'}
    if {row['id'] for row in rows} != allowed:
        sys.exit('Unexpected plugin set; review probe isolation before running.')
    if any(not Path(row['artifact']).is_file() for row in rows):
        sys.exit('Build plugin artifacts before running this probe.')
    # No inherited model/database credentials, collector config, or Java options.
    env = {'PATH': os.environ['PATH'],
           'DEEPSEEK_API_KEY': 'fictional-smoke-key-no-model-calls'}
    with tempfile.TemporaryDirectory(prefix='ailiao-plugin-smoke-') as temp:
        launcher.ROOT = Path(temp)
        bootstrap = launcher.write_bootstrap(rows)
        child = subprocess.Popen(
            [os.environ.get('JAVA_BIN', 'java'), '-Dfile.encoding=UTF-8', '-jar',
             str(base / 'app-boot/target/dsh-java.jar'), '--dsh.bootstrap=' + str(bootstrap),
             '--spring.main.web-application-type=none'], env=env, cwd=ROOT,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        forced_stop = False
        try:
            output, _ = child.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            forced_stop = True
            child.terminate()
            try:
                output, _ = child.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                output, _ = child.communicate()
        # Read all buffered output, including after a successful natural exit.
        ready = re.search(r'F01 bootstrap ready: configured=9, active=9\b', output)
        passed = bool(ready) and (forced_stop or child.returncode == 0)
        print('DSH plugin assembly: ' + ('PASS (9 configured, 9 active)' if passed else 'FAIL'))
        print('No business DB plugin; no model request; synthetic key; captured logs not persisted.')
        return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
