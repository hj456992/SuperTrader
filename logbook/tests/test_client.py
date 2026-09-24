from pathlib import Path
import sys,tempfile,threading,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from logbook.server import Application,create_server
from logbook.client import LogClient
from test_archive import bundle,TEXT

class ClientTests(unittest.TestCase):
    def test_viewer_reads_independent_logger_and_reports_offline(self):
        with tempfile.TemporaryDirectory() as d:
            app=Application(Path(d)/'data',Path(d),watch=False)
            server=create_server(app,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            client=LogClient(Path(d)/'data/service.token',server.server_port)
            try:
                self.assertEqual(client.state()['group']['messageCount'],0)
                app.store.import_archive(bundle(TEXT),'native.zip','卧底不追高')
                self.assertEqual(client.state()['segments'][0]['messages'][0]['text'],'第一行\n第二行')
                app.store.set_database_source('fixture-account','fixture@chatroom','卧底不追高',status='setup_required')
                self.assertEqual(client.state()['source']['type'],'wechat_database')
                self.assertEqual(client.state()['source']['status'],'setup_required')
            finally:server.shutdown();server.server_close();app.close()
            self.assertEqual(client.state()['source']['status'],'offline')
    def test_missing_token_does_not_invent_saved_records(self):
        with tempfile.TemporaryDirectory() as d:
            state=LogClient(Path(d)/'missing').state()
            self.assertEqual(state['source']['status'],'offline');self.assertEqual(state['segments'],[])
