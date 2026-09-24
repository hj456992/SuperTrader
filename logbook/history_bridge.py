"""Authenticated sink for the dsh-java wechat-cli history plugin."""
import threading,time
from .archive import TARGET

class HistoryBridge:
    def __init__(self,store):
        self.store=store;self.lock=threading.RLock();self.updated=0
        self.status='setup_required';self.detail='wechat-cli history 插件正在等待首次连接。'
    def checkpoint(self):
        with self.store.lock:
            rows=self.store.db.execute('SELECT checkpoint FROM database_sources WHERE checkpoint IS NOT NULL').fetchall()
            if len(rows)>1:raise ValueError('存在多个账号检查点，需要明确来源后继续。')
            import json
            return dict(checkpoint=json.loads(rows[0][0]) if rows else None)
    def update(self,value):
        allowed={'starting','setup_required','syncing','connected','error','stopped'}
        status=value.get('status')
        if status not in allowed:raise ValueError('采集状态无效。')
        # Caller details may contain CLI stderr or paths. Return only our fixed wording.
        messages={'starting':'正在启动 dsh-java 采集插件。','setup_required':'wechat-cli history 尚未完成首次读取初始化。','syncing':'正在通过 history 同步本机群记录。','connected':'wechat-cli history 已连接，正在自动同步。','error':'history 读取或保存未完成，保留原有同步进度。','stopped':'history 采集插件已停止。'}
        with self.lock:self.status=status;self.detail=messages[status];self.updated=time.monotonic()
    def ingest(self,value):
        with self.lock:
            return self._ingest(value)
    def _ingest(self,value):
        if not isinstance(value,dict) or value.get('schema')!='aichat.history.v1' or value.get('target')!=TARGET:raise ValueError('不支持的群消息来源。')
        account=value.get('account_fingerprint');chat=value.get('chat_id');checkpoint=value.get('checkpoint');rows=value.get('rows')
        if not isinstance(account,str) or len(account)!=64 or any(c not in '0123456789abcdef' for c in account):raise ValueError('账号标识无效。')
        if not isinstance(chat,str) or not chat.endswith('@chatroom'):raise ValueError('群标识无效。')
        if not isinstance(checkpoint,dict) or checkpoint.get('account')!=account or checkpoint.get('chat')!=chat:raise ValueError('检查点与群不一致。')
        cursor=checkpoint.get('cursor');salts=checkpoint.get('salts')
        if not isinstance(cursor,dict) or not isinstance(salts,dict) or set(cursor)!=set(salts):raise ValueError('检查点不完整。')
        if not all(isinstance(k,str) and type(v) is int and v>=0 for k,v in cursor.items()):raise ValueError('检查点位置无效。')
        if not all(isinstance(v,str) and len(v)==32 and all(c in '0123456789abcdef' for c in v) for v in salts.values()):raise ValueError('数据库标识无效。')
        if type(value.get('has_more')) is not bool:raise ValueError('分页状态无效。')
        if not isinstance(rows,list) or len(rows)>2000:raise ValueError('消息批次过大。')
        for row in rows:
            if not isinstance(row,dict) or row.get('database') not in cursor or type(row.get('local_id')) is not int or row['local_id']>cursor[row['database']]:raise ValueError('消息不在检查点范围内。')
        current=self.checkpoint()['checkpoint']
        if current and (current.get('account')!=account or current.get('chat')!=chat):raise ValueError('当前日志来源发生变化。')
        if current:
            for name,prior in current['cursor'].items():
                if name not in cursor or cursor[name]<prior or current['salts'].get(name)!=salts.get(name):raise ValueError('检查点倒退或数据库变更。')
        result=self.store.import_database_batch(account,chat,TARGET,rows,checkpoint)
        self.update(dict(status='syncing' if value.get('has_more') else 'connected'))
        return result
    def state(self):
        with self.lock:
            stale=self.updated and time.monotonic()-self.updated>90
            return dict(type='wechat_cli_history',name='wechat-cli history · dsh-java 插件',status='offline' if stale else self.status,detail='采集插件长时间未更新，已保留本地记录。' if stale else self.detail,adapter='dsh-java/wechat-history')
