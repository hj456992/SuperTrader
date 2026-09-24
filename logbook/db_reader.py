"""Read only the uniquely resolved authorized conversation from local DB + WAL."""
import ctypes
import hashlib
from pathlib import Path
from .archive import TARGET
from .sqlcipher import DB,load_key

class DatabaseReadError(RuntimeError):pass

def literal(value):return "CAST(x'"+str(value).encode().hex()+"' AS TEXT)"

def generation(path):
    with path.open('rb') as stream:
        import os
        stat=os.fstat(stream.fileno())
        return stat.st_dev,stat.st_ino,stream.read(16)

def decode_text(raw):
    if len(raw)>2*1024*1024:raise DatabaseReadError('message_too_large')
    if raw.startswith(b'\x28\xb5\x2f\xfd'):
        lib=None
        for path in ('/opt/homebrew/opt/zstd/lib/libzstd.dylib','/usr/local/opt/zstd/lib/libzstd.dylib'):
            if Path(path).is_file():lib=ctypes.CDLL(path);break
        if lib is None:raise DatabaseReadError('zstd_unavailable')
        lib.ZSTD_decompress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t];lib.ZSTD_decompress.restype=ctypes.c_size_t
        lib.ZSTD_isError.argtypes=[ctypes.c_size_t];lib.ZSTD_isError.restype=ctypes.c_uint
        buf=ctypes.create_string_buffer(2*1024*1024);size=lib.ZSTD_decompress(buf,len(buf),raw,len(raw))
        if lib.ZSTD_isError(size):raise DatabaseReadError('compressed_message_invalid')
        raw=buf.raw[:size]
    try:return raw.decode('utf-8','strict')
    except UnicodeDecodeError:raise DatabaseReadError('message_encoding_unsupported') from None

class DatabaseReader:
    def __init__(self,root,key_file,target=TARGET):
        if target!=TARGET:raise DatabaseReadError('target_not_authorized')
        self.root=Path(root).resolve();self.keys=Path(key_file).resolve();self.target=target
        self.account=hashlib.sha256(str(self.root).encode()).hexdigest()
    def open(self,path):return DB(path,load_key(self.keys,path))
    def resolve(self):
        with self.open(self.root/'contact/contact.db') as db:
            matches=db.query('SELECT username FROM contact WHERE nick_name='+literal(self.target)+' OR remark='+literal(self.target))
        if len(matches)!=1 or not matches[0]['username'].endswith('@chatroom'):raise DatabaseReadError('target_not_unique')
        return matches[0]['username']
    def read_batch(self,checkpoint=None,limit=200):
        if not 1<=limit<=200:raise ValueError('limit must be 1..200')
        chat=self.resolve();table='Msg_'+hashlib.md5(chat.encode()).hexdigest()
        if checkpoint and (checkpoint.get('account')!=self.account or checkpoint.get('chat')!=chat):raise DatabaseReadError('source_identity_changed')
        old=(checkpoint or {}).get('cursor',{});old_salts=(checkpoint or {}).get('salts',{})
        cursor=dict(old);salts={};rows=[];found=[];paths=sorted((self.root/'message').glob('message_*.db'))
        if not paths:raise DatabaseReadError('message_databases_missing')
        for path in paths:
            before=generation(path)
            with self.open(path) as db:
                db.query('BEGIN')
                if not db.query('SELECT 1 FROM sqlite_master WHERE type=\'table\' AND name='+literal(table)):continue
                found.append(path.name)
                with path.open('rb') as f:salts[path.name]=f.read(16).hex()
                maximum=int(db.query(f'SELECT coalesce(max(local_id),0) AS n FROM "{table}"')[0]['n'])
                if path.name in old and (old_salts.get(path.name)!=salts[path.name] or maximum<int(old[path.name])):raise DatabaseReadError('database_replaced_or_cursor_regressed')
                after=int(old.get(path.name,0))
                records=db.query(f'SELECT local_id,server_id,local_type,create_time,real_sender_id,hex(message_content) AS body FROM "{table}" WHERE local_id>{after} ORDER BY local_id ASC LIMIT {limit}')
                for row in records:
                    sender=db.query('SELECT user_name FROM Name2Id WHERE rowid='+str(int(row['real_sender_id'])))
                    if len(sender)!=1 or not sender[0]['user_name']:raise DatabaseReadError('sender_identity_missing')
                    sender=sender[0]['user_name'];kind=int(row['local_type']);raw=bytes.fromhex(row['body'] or '')
                    text=decode_text(raw) if kind==1 else None
                    if text is not None and text.startswith(sender+':\n'):text=text[len(sender)+2:]
                    item={k:int(row[k] or 0) for k in ('local_id','server_id','local_type','create_time')}
                    item.update(database=path.name,sender_id=sender,text=text);rows.append(item)
                    cursor[path.name]=max(cursor.get(path.name,0),item['local_id'])
                cursor.setdefault(path.name,after)
            if generation(path)!=before:raise DatabaseReadError('database_replaced_or_cursor_regressed')
        if not found:raise DatabaseReadError('target_has_no_local_history')
        if set(old)-set(found):raise DatabaseReadError('database_replaced_or_cursor_regressed')
        with self.open(self.root/'contact/contact.db') as db:
            names={}
            for sender in {r['sender_id'] for r in rows}:
                result=db.query('SELECT nick_name,remark FROM contact WHERE username='+literal(sender))
                names[sender]=(result[0]['remark'] or result[0]['nick_name'] or sender) if len(result)==1 else sender
        for row in rows:row['author']=names[row['sender_id']]
        rows.sort(key=lambda r:(r['create_time'],r['database'],r['local_id']))
        return dict(account_fingerprint=self.account,chat_id=chat,rows=rows,checkpoint=dict(account=self.account,chat=chat,cursor=cursor,salts=salts),has_more=any(sum(r['database']==name for r in rows)==limit for name in found))
