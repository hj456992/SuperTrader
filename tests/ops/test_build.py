"""Exercise the build entry point with synthetic Maven/npm executables."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BuildTest(unittest.TestCase):
    def test_build_packages_required_local_plugin_and_runs_java_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy(ROOT / 'build.sh', root / 'build.sh')
            (root / 'web').mkdir()
            (root / 'plugins/feishu-history').mkdir(parents=True)
            for name in ('mvn', 'npm'):
                executable = root / name
                executable.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$BUILD_TEST_LOG"\n')
                executable.chmod(0o700)
            env = {'PATH': str(root) + ':' + os.environ['PATH'],
                   'MAVEN_BIN': str(root / 'mvn'), 'BUILD_TEST_LOG': str(root / 'calls')}
            result = subprocess.run(['zsh', str(root / 'build.sh')], env=env,
                                    cwd=root.parent, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = (root / 'calls').read_text().splitlines()
            self.assertTrue(any('plugins/feishu-history/pom.xml' in call and 'package' in call for call in calls))
            self.assertFalse(any('skipTests' in call for call in calls))
            self.assertIn('--prefix web run build', calls)
            self.assertTrue((root / 'web/dist/vendor').is_dir())


if __name__ == '__main__':
    unittest.main()
