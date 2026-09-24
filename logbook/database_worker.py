"""Local continuous DB reader; no UI actions, no network and no key extraction."""
import json
import fcntl
import os
import threading
from datetime import datetime,timezone
from pathlib import Path
from .archive import TARGET
from .db_reader import DatabaseReader,DatabaseReadError

DETAILS={
 'target_not_unique':'目标群名称无法唯一对应本机群 ID，已暂停读取。',
 'target_has_no_local_history':'本机暂未找到目标群的消息表。',
 'source_identity_changed':'账号或群标识变化，已暂停读取。',
 'database_replaced_or_cursor_regressed':'微信数据库已变更，需要重新核验后继续。',
 'sender_identity_missing':'成员标识尚未解析，保留当前进度等待处理。',
}

class DatabaseWorker:
    def __init__(self,store,config=None,start=True,interval=2):
        self.store=store;self.config=Path(config or store.root/'database-source.json')
        self.interval=interval;self.stop=threading.Event();self.lock=threading.Lock()
        self._state=dict(type='wechat_database',name='聊天日志',status='setup_required',detail='首次连接尚未完成，当前未自动读取微信消息。',lastCheckedAt=None,targetVerified=False)
        self.thread=None
        self.ownership=None
        if start:
            descriptor=os.open(store.root/'.database-reader.lock',os.O_CREAT|os.O_RDWR,0o600)
            self.ownership=os.fdopen(descriptor,'a')
            try:fcntl.flock(self.ownership.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:
                self.ownership.close();self.ownership=None
                self._update(status='error',detail='已有一个本地日志采集进程，当前进程不重复读取。')
                return
            self.thread=threading.Thread(target=self._run,daemon=True,name='wechat-database-log');self.thread.start()
    def state(self):
        with self.lock:return dict(self._state)
    def _update(self,**values):
        with self.lock:self._state.update(values)
    def sync_once(self):
        account=chat=None
        try:
            if not self.config.is_file():
                self._update(status='setup_required',detail='首次连接尚未完成，当前未自动读取微信消息。',targetVerified=False);return False
            if self.config.is_symlink() or self.config.stat().st_mode & 0o077:raise ValueError('private_config_required')
            value=json.loads(self.config.read_text())
            if not isinstance(value,dict) or value.get('targetName')!=TARGET:raise ValueError('invalid_config')
            root=Path(value['dbRoot']);keys=Path(value['keyFile'])
            if not root.is_absolute() or not keys.is_absolute():raise ValueError('absolute_path_required')
            if not keys.is_file():
                self._update(status='setup_required',detail='本机数据库已找到；首次读取初始化尚未完成，当前未自动采集。',targetVerified=False);return False
            reader=DatabaseReader(root,keys);account=reader.account;chat=reader.resolve()
            checkpoint=self.store.get_database_checkpoint(account,chat)
            batch=reader.read_batch(checkpoint)
            if batch['account_fingerprint']!=account or batch['chat_id']!=chat:
                raise DatabaseReadError('source_identity_changed')
            if self.stop.is_set():return False
            # The message batch and its checkpoint must commit together.
            result=self.store.import_database_batch(account,chat,TARGET,batch['rows'],batch['checkpoint'])
            checked=datetime.now(timezone.utc).isoformat(timespec='seconds')
            detail='正在同步本机已有记录。' if batch['has_more'] else '已连接本机微信数据库，自动检查目标群新增记录。'
            status='syncing' if batch['has_more'] else 'connected'
            self.store.set_database_source(account,chat,TARGET,status=status,detail=detail)
            self._update(status=status,detail=detail,lastCheckedAt=checked,targetVerified=True,lastBatchAdded=result['added'])
            return batch['has_more']
        except Exception as error:
            detail=DETAILS.get(str(error),'本机数据库读取未完成，已保留上次同步进度；需要核验密钥或版本适配。')
            self._update(status='error',detail=detail,targetVerified=False)
            if account and chat:
                try:self.store.set_database_source(account,chat,TARGET,status='error',detail=detail)
                except Exception:pass
            return False
    def _run(self):
        while not self.stop.is_set():
            more=self.sync_once()
            self.stop.wait(0.1 if more else self.interval)
    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=10)
        if self.thread and self.thread.is_alive():return False
        if self.ownership:self.ownership.close();self.ownership=None
        return True
