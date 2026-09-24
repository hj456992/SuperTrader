"""Private log storage for native exports and incremental local database reads."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import threading
import uuid

from .archive import TARGET, parse_archive


def now():return datetime.now(timezone.utc).isoformat(timespec='seconds')

def private_write(path, data):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    try:
        with os.fdopen(fd,'wb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
    finally:
        if temporary.exists():temporary.unlink()


class LogStore:
    def __init__(self, root):
        self.root=Path(root).resolve();self.root.mkdir(parents=True,exist_ok=True,mode=0o700);self.root.chmod(0o700)
        self.archive_dir=self.root/'archives';self.archive_dir.mkdir(exist_ok=True,mode=0o700)
        self.files_dir=self.root/'files';self.files_dir.mkdir(exist_ok=True,mode=0o700)
        self.lock=threading.RLock()
        self.db=sqlite3.connect(self.root/'messages.sqlite3',check_same_thread=False)
        (self.root/'messages.sqlite3').chmod(0o600)
        self.db.row_factory=sqlite3.Row
        self.db.executescript('''
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS batches(id TEXT PRIMARY KEY,digest TEXT UNIQUE NOT NULL,filename TEXT NOT NULL,imported TEXT NOT NULL,count INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id TEXT NOT NULL REFERENCES batches(id),ordinal INTEGER NOT NULL,author TEXT NOT NULL,body TEXT NOT NULL,sent_label TEXT NOT NULL,kind TEXT NOT NULL,UNIQUE(batch_id,ordinal));
        CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY,batch_id TEXT NOT NULL REFERENCES batches(id),path TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS message_files(message_id INTEGER NOT NULL REFERENCES messages(id),attachment_id TEXT NOT NULL REFERENCES attachments(id),PRIMARY KEY(message_id,attachment_id));
        CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY,value INTEGER NOT NULL);
        INSERT OR IGNORE INTO counters VALUES('duplicates',0);
        CREATE TABLE IF NOT EXISTS database_sources(
            account TEXT NOT NULL,chat_id TEXT NOT NULL,group_name TEXT NOT NULL,
            status TEXT NOT NULL,detail TEXT,updated TEXT NOT NULL,checkpoint TEXT,
            PRIMARY KEY(account,chat_id));
        CREATE TABLE IF NOT EXISTS database_batches(
            id TEXT PRIMARY KEY,account TEXT NOT NULL,chat_id TEXT NOT NULL,
            imported TEXT NOT NULL,count INTEGER NOT NULL,
            FOREIGN KEY(account,chat_id) REFERENCES database_sources(account,chat_id));
        CREATE TABLE IF NOT EXISTS database_messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id TEXT NOT NULL REFERENCES database_batches(id),
            account TEXT NOT NULL,chat_id TEXT NOT NULL,database_name TEXT NOT NULL,
            local_id INTEGER NOT NULL,server_id TEXT,sender_id TEXT,author TEXT NOT NULL,
            body TEXT NOT NULL,create_time INTEGER NOT NULL,local_type INTEGER NOT NULL,
            UNIQUE(account,chat_id,database_name,local_id));
        CREATE INDEX IF NOT EXISTS database_messages_recent ON database_messages(create_time DESC,id DESC);
        ''')
        self.db.commit()

    @staticmethod
    def _database_identity(account_fingerprint, chat_id, group=TARGET):
        if group != TARGET:
            raise ValueError('只能采集指定群日志。')
        if not isinstance(account_fingerprint, str) or not account_fingerprint.strip():
            raise ValueError('需要稳定账号指纹。')
        if not isinstance(chat_id, str) or not chat_id.strip():
            raise ValueError('需要已解析的稳定群标识。')

    def set_database_source(self, account_fingerprint, chat_id, group, status='starting', detail=None):
        """Persist worker status. Pass safe user-facing detail, never keys or DB paths.

        This method does not advance the checkpoint or imply successful acquisition.
        Reconfiguration preserves previously committed messages and checkpoint.
        """
        self._database_identity(account_fingerprint, chat_id, group)
        if not isinstance(status, str) or not status.strip():
            raise ValueError('需要采集状态。')
        if detail is not None and not isinstance(detail, str):
            raise ValueError('状态说明必须是文本。')
        with self.lock, self.db:
            self.db.execute('''INSERT INTO database_sources VALUES(?,?,?,?,?,?,NULL)
                ON CONFLICT(account,chat_id) DO UPDATE SET status=excluded.status,
                detail=excluded.detail,updated=excluded.updated''',
                (account_fingerprint, chat_id, group, status, detail, now()))

    def get_database_checkpoint(self, account_fingerprint, chat_id):
        """Return the last committed JSON checkpoint or None; does not configure a source."""
        self._database_identity(account_fingerprint, chat_id)
        with self.lock:
            row = self.db.execute('SELECT checkpoint FROM database_sources WHERE account=? AND chat_id=?',
                                  (account_fingerprint, chat_id)).fetchone()
            return json.loads(row['checkpoint']) if row and row['checkpoint'] is not None else None

    def import_database_batch(self, account_fingerprint, chat_id, group, rows, checkpoint):
        """Atomically persist rows and an opaque JSON-object checkpoint for one source.

        Each row requires database, local_id, server_id, sender_id, author, text,
        create_time (Unix seconds), local_type. Identity is account+chat+database+
        local_id; callers must use a stable database name rather than a temp path.
        The worker owns checkpoint ordering and must have one reader per source.
        Empty/replayed batches can advance checkpoint but create no empty segment.
        """
        self._database_identity(account_fingerprint, chat_id, group)
        if not isinstance(checkpoint, dict):
            raise ValueError('检查点必须是 JSON 对象。')
        encoded = json.dumps(checkpoint, ensure_ascii=False, allow_nan=False)
        batch, created = uuid.uuid4().hex, now()
        added = duplicates = 0
        with self.lock, self.db:
            self.db.execute('INSERT OR IGNORE INTO database_sources VALUES(?,?,?,?,?,?,NULL)',
                            (account_fingerprint, chat_id, group, 'configured', None, created))
            self.db.execute('INSERT INTO database_batches VALUES(?,?,?,?,0)',
                            (batch, account_fingerprint, chat_id, created))
            for row in rows:
                database = row['database']
                local_id, stamp, local_type = row['local_id'], row['create_time'], row['local_type']
                if not isinstance(database, str) or not database:
                    raise ValueError('消息需要来源数据库标识。')
                if any(type(value) is not int for value in (local_id, stamp, local_type)) or local_id < 0:
                    raise ValueError('消息标识、时间和类型必须是整数。')
                # Validate timestamp range before committing so state() cannot fail later.
                datetime.fromtimestamp(stamp, timezone.utc)
                author, body, sender = row['author'], row['text'], row['sender_id']
                if body is None and local_type & 0xffff != 1:
                    body = ''
                if not isinstance(author, str) or not isinstance(body, str) or (sender is not None and not isinstance(sender, str)):
                    raise ValueError('消息成员和正文必须是文本。')
                server = row['server_id']
                if server is not None and (isinstance(server, bool) or not str(server).isdigit()):
                    raise ValueError('来源消息标识必须是非负整数。')
                server = str(server) if server is not None and int(server) != 0 else None
                result = self.db.execute('''INSERT INTO database_messages(
                    batch_id,account,chat_id,database_name,local_id,server_id,sender_id,
                    author,body,create_time,local_type) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(account,chat_id,database_name,local_id) DO NOTHING''',
                    (batch,account_fingerprint,chat_id,database,local_id,server,sender or None,
                     author,body,stamp,local_type))
                added += result.rowcount
                duplicates += 1 - result.rowcount
            if added:
                self.db.execute('UPDATE database_batches SET count=? WHERE id=?', (added,batch))
            else:
                self.db.execute('DELETE FROM database_batches WHERE id=?', (batch,))
            self.db.execute('UPDATE database_sources SET checkpoint=?,updated=? WHERE account=? AND chat_id=?',
                            (encoded,created,account_fingerprint,chat_id))
            self.db.execute("UPDATE counters SET value=value+? WHERE name='duplicates'", (duplicates,))
        return dict(ok=True, added=added, duplicate=bool(duplicates and not added),
                    duplicates=duplicates, batchId=batch if added else None)

    @staticmethod
    def _database_body(row):
        kind = row['local_type'] & 0xffff
        if kind == 1:
            return 'text', row['body']
        types = {3: ('image','[图片]'), 34: ('voice','[语音]'), 43: ('video','[视频]'),
                 47: ('sticker','[表情]'), 48: ('location','[位置]'),
                 49: ('attachment','[分享或附件]'), 10000: ('system','[系统消息]')}
        return types.get(kind, ('unsupported', '[消息类型 %s]' % row['local_type']))

    def import_archive(self,data,filename,group):
        if group!=TARGET:raise ValueError('只能导入指定群日志。')
        digest=hashlib.sha256(data).hexdigest()
        with self.lock:
            prior=self.db.execute('SELECT id FROM batches WHERE digest=?',(digest,)).fetchone()
            if prior:
                with self.db:self.db.execute("UPDATE counters SET value=value+1 WHERE name='duplicates'")
                return dict(ok=True,added=0,duplicate=True,batchId=prior['id'])
            parsed=parse_archive(data)
            batch=uuid.uuid4().hex;created=now()
            name=Path(filename).name[:200] or '微信导出.zip'
            written=[]
            try:
                archive=self.archive_dir/(batch+'.zip');private_write(archive,data);written.append(archive)
                file_rows={}
                for source,body in parsed['files'].items():
                    key=uuid.uuid4().hex;path=self.files_dir/key;private_write(path,body);written.append(path)
                    file_rows[source]=(key,batch,str(path.relative_to(self.root)),Path(source).name,len(body))
                with self.db:
                    self.db.execute('INSERT INTO batches VALUES(?,?,?,?,?)',(batch,digest,name,created,len(parsed['messages'])))
                    self.db.executemany('INSERT INTO attachments VALUES(?,?,?,?,?)',file_rows.values())
                    for index,m in enumerate(parsed['messages']):
                        record=self.db.execute('INSERT INTO messages(batch_id,ordinal,author,body,sent_label,kind) VALUES(?,?,?,?,?,?)',(batch,index,m['author'],m['text'],m['sentAtLabel'],m['kind'])).lastrowid
                        for source in set(m['attachments']):self.db.execute('INSERT INTO message_files VALUES(?,?)',(record,file_rows[source][0]))
            except Exception:
                for path in written:path.unlink(missing_ok=True)
                raise
            return dict(ok=True,added=len(parsed['messages']),duplicate=False,batchId=batch)

    def state(self):
        with self.lock:
            members=[dict(id=hashlib.sha256(r[0].encode()).hexdigest()[:20],name=r[0]) for r in self.db.execute('SELECT DISTINCT author FROM messages ORDER BY author')]
            member_ids={m['name']:m['id'] for m in members}
            batches=[]
            for b in self.db.execute('SELECT * FROM batches ORDER BY imported,id').fetchall():
                messages=[]
                for r in self.db.execute('SELECT * FROM messages WHERE batch_id=? ORDER BY ordinal',(b['id'],)).fetchall():
                    files=[dict(id=a['id'],name=a['name'],size=a['size']) for a in self.db.execute('SELECT a.id,a.name,a.size FROM attachments a JOIN message_files mf ON mf.attachment_id=a.id WHERE mf.message_id=?',(r['id'],))]
                    messages.append(dict(id=r['id'],memberId=member_ids[r['author']],author=r['author'],text=r['body'],kind=r['kind'],sentAtLabel=r['sent_label'],timeLabel=r['sent_label'],timePrecision='minute',capturedAt=b['imported'],sourceMessageId=None,source='wechat_export',attachments=files))
                batches.append(dict(id=b['id'],createdAt=b['imported'],filename=b['filename'],gapBefore='export_batch',messages=messages))
            database_members = {}
            recent_rows=self.db.execute('''SELECT m.*,b.imported FROM database_messages m
                JOIN database_batches b ON b.id=m.batch_id WHERE m.id IN
                (SELECT id FROM database_messages ORDER BY create_time DESC,id DESC LIMIT 2000)
                ORDER BY b.imported,b.rowid,m.id''').fetchall()
            recent_batches={}
            for r in recent_rows:recent_batches.setdefault(r['batch_id'],[]).append(r)
            for batch_id, rows in recent_batches.items():
                messages=[]
                for r in rows:
                    # Missing sender identity is deliberately not merged by display nickname.
                    identity = [r['account'],r['chat_id'],r['sender_id']] if r['sender_id'] else [r['account'],r['chat_id'],r['database_name'],r['local_id']]
                    member_id = 'db-' + hashlib.sha256(json.dumps(identity,ensure_ascii=False).encode()).hexdigest()[:24]
                    database_members[member_id] = dict(id=member_id,name=r['author'],identity='source_id' if r['sender_id'] else 'unknown')
                    stamp=datetime.fromtimestamp(r['create_time'],timezone.utc)
                    label=stamp.astimezone().strftime('%Y年%m月%d日 %H:%M:%S')
                    kind,body=self._database_body(r)
                    messages.append(dict(id='db-'+str(r['id']),memberId=member_id,author=r['author'],text=body,
                        kind=kind,sentAt=stamp.isoformat(timespec='seconds'),sentAtLabel=label,timeLabel=label,
                        timePrecision='second',createTime=r['create_time'],capturedAt=r['imported'],
                        sourceMessageId=r['server_id'],sourceLocalId=r['local_id'],source='wechat_database',
                        sourceSenderId=r['sender_id'],localType=r['local_type'],attachments=[]))
                batches.append(dict(id=batch_id,createdAt=rows[0]['imported'],filename='本机数据库增量',
                                    gapBefore='database_batch',source='wechat_database',messages=messages))
            members.extend(database_members.values())
            batches.sort(key=lambda batch: batch['createdAt'])
            export_count=self.db.execute('SELECT COUNT(*) FROM messages').fetchone()[0]
            database_count=self.db.execute('SELECT COUNT(*) FROM database_messages').fetchone()[0]
            count=export_count+database_count
            source_row=self.db.execute('SELECT * FROM database_sources ORDER BY updated DESC,rowid DESC LIMIT 1').fetchone()
            if source_row:
                source=dict(type='wechat_database',name='聊天日志',status=source_row['status'],
                            lastImportAt=batches[-1]['createdAt'] if batches else None,updatedAt=source_row['updated'])
                if source_row['detail'] is not None:source['detail']=source_row['detail']
                coverage='展示最近 2000 条已保存的数据库消息，总消息数包含全部已保存记录；未保证全部历史已同步，非文本仅展示类型。保留的导出批次仍按导入时指定群归属。'
            else:
                source=dict(type='wechat_export',name='聊天日志',status='connected',lastImportAt=batches[-1]['createdAt'] if batches else None)
                coverage='只包含已导入的微信原生导出包。不同批次可能重叠；群归属由导入时指定，成员按显示昵称区分。'
            identity=('mixed' if export_count else 'database_id') if source_row else 'assigned_at_import'
            return dict(group=dict(name=TARGET,messageCount=count,memberCount=len(members),identity=identity),members=members,segments=batches,
                        stats=dict(observations=len(batches),duplicates=self.db.execute("SELECT value FROM counters WHERE name='duplicates'").fetchone()[0]),
                        source=source,coverage=dict(complete=False,description=coverage))

    def attachment(self,key):
        with self.lock:
            row=self.db.execute('SELECT path,name FROM attachments WHERE id=?',(key,)).fetchone()
            if not row:raise KeyError(key)
            path=self.root/row['path']
            if path.is_symlink() or not path.resolve().is_relative_to(self.files_dir):raise KeyError(key)
            return path,row['name']

    def close(self):
        with self.lock:self.db.close()
