from pathlib import Path
import sys,tempfile,time,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.server import Application
from test_archive import bundle,TEXT

class WatcherTests(unittest.TestCase):
    def test_only_completed_inbox_zip_is_imported_and_symlink_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);app=Application(root/'data',root)
            try:
                outside=root/'elsewhere.zip';outside.write_bytes(bundle(TEXT))
                inbox=root/'data/inbox';(inbox/'shortcut.zip').symlink_to(outside)
                (inbox/'incoming.zip.part').write_bytes(bundle(TEXT))
                time.sleep(.1)
                self.assertEqual(app.state()['group']['messageCount'],0)
                (inbox/'incoming.zip.part').rename(inbox/'incoming.zip')
                deadline=time.monotonic()+7
                while time.monotonic()<deadline and app.state()['group']['messageCount']!=2:time.sleep(.05)
                self.assertEqual(app.state()['group']['messageCount'],2)
                self.assertEqual(app.state()['stats']['observations'],1)
                self.assertEqual(app.state()['stats']['duplicates'],0)
            finally:app.close()
