"""Watch only the product's explicit import inbox; never scan WeChat storage."""
import hashlib
from pathlib import Path
import threading
from .archive import TARGET,MAX_ARCHIVE,ArchiveError
from .store import now,private_write

class InboxWatcher:
    def __init__(self,store):
        self.store=store;self.inbox=store.root/'inbox';self.inbox.mkdir(exist_ok=True,mode=0o700)
        self.stop=threading.Event();self.lock=threading.Lock();self.seen={};self.done={};self.last=None;self.error=None
        self.thread=threading.Thread(target=self.run,daemon=True,name='logbook-inbox');self.thread.start()
    def poll(self):
        for path in self.inbox.glob('*.zip'):
            if path.is_symlink() or not path.is_file():continue
            info=path.stat();signature=(info.st_mtime_ns,info.st_size)
            if self.done.get(path)==signature:continue
            if self.seen.get(path)!=signature:self.seen[path]=signature;continue
            try:
                if info.st_size>MAX_ARCHIVE:raise ArchiveError('收件文件超过 64 MB，未导入。')
                data=path.read_bytes()
                if path.is_symlink() or (path.stat().st_mtime_ns,path.stat().st_size)!=signature:continue
                self.store.import_archive(data,path.name,TARGET)
                with self.lock:self.last=now();self.error=None
            except ArchiveError as error:
                with self.lock:self.error=str(error)
            except OSError:
                with self.lock:self.error='收件文件暂时无法读取。'
                continue
            self.done[path]=signature
    def run(self):
        while not self.stop.is_set():
            try:self.poll()
            except Exception:
                with self.lock:self.error='收件目录检查未完成，稍后重试。'
            self.stop.wait(2)
    def state(self):
        with self.lock:return dict(status='watching_inbox',lastImportAt=self.last,error=self.error)
    def close(self):self.stop.set();self.thread.join(timeout=5)
