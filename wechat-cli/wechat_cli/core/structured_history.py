"""Authorized structured history orchestration; never initializes global state."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import tempfile

from .messages import collect_structured_history
from .readonly_cipher import ReadonlyCipher


def private_json(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError('configuration/checkpoint/key file must be private and owned')
    with path.open(encoding='utf-8') as stream:
        return json.load(stream)


def private_dir(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError('cache directory must be private and owned')


def _signature(path):
    try:
        s = path.stat()
        return (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
    except FileNotFoundError:
        return None


def validate_wal(path):
    """Reject corruption rather than silently returning an older committed prefix.

    Only validates encrypted bytes. SQLite still selects committed frames and
    SQLCipher authenticates decrypted pages. Old-generation tail frames after
    WAL reset are ignored by their differing salt, matching SQLite semantics.
    """
    if not path.exists() or path.stat().st_size == 0:
        return
    with path.open('rb') as stream:
        header = stream.read(32)
        if len(header) != 32:
            raise ValueError('truncated WAL header')
        magic, version, page_size = struct.unpack('>III', header[:12])
        if magic not in (0x377f0682, 0x377f0683) or version != 3007000 or page_size != 4096:
            raise ValueError('unsupported WAL header')
        endian = '<' if magic == 0x377f0682 else '>'
        def checksum(data, seed):
            words = struct.unpack(endian + str(len(data) // 4) + 'I', data)
            one, two = seed
            for i in range(0, len(words), 2):
                one = (one + words[i] + two) & 0xffffffff
                two = (two + words[i + 1] + one) & 0xffffffff
            return one, two
        rolling = checksum(header[:24], (0, 0))
        if rolling != struct.unpack('>II', header[24:32]):
            raise ValueError('WAL header checksum mismatch')
        while True:
            frame = stream.read(24 + page_size)
            if not frame:
                return
            if len(frame) < 24:
                raise ValueError('truncated WAL frame')
            if frame[8:16] != header[16:24]:
                return
            if len(frame) != 24 + page_size or struct.unpack('>I', frame[:4])[0] == 0:
                raise ValueError('invalid WAL frame')
            rolling = checksum(frame[:8] + frame[24:], rolling)
            if rolling != struct.unpack('>II', frame[16:24]):
                raise ValueError('WAL frame checksum mismatch')


def snapshot(source, destination):
    """Only encrypted DB and WAL are copied; native SQLite rebuilds private SHM.

    Refuse a concurrently changing copy, rather than emit a mixed snapshot.
    """
    paths = [source, Path(str(source) + '-wal')]
    before = [_signature(p) for p in paths]
    if before[0] is None: raise ValueError('missing database')
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    for src, signature, suffix in zip(paths, before, ['', '-wal']):
        if signature is not None:
            dst = Path(str(destination) + suffix)
            with src.open('rb') as inp, dst.open('xb') as out:
                os.chmod(dst, 0o600)
                shutil.copyfileobj(inp, out, 1024 * 1024)
    if before != [_signature(p) for p in paths]:
        raise ValueError('database changed while snapshotting; retry next poll')
    validate_wal(Path(str(destination) + '-wal'))
    return destination


def _key(keys, rel):
    candidates = []
    for name, value in keys.items():
        if name.replace('\\', '/') == rel:
            if isinstance(value, dict): value = value.get('enc_key', value.get('key'))
            if isinstance(value, str) and re.fullmatch('[0-9a-fA-F]{64}', value): candidates.append(value.lower())
    if len(set(candidates)) != 1: raise ValueError('database key missing or ambiguous')
    return candidates[0]


def structured_history(config_path, target, cursor_path, limit):
    if not config_path or not cursor_path or not 1 <= limit <= 200:
        raise ValueError('structured history needs config, cursor-file and limit 1..200')
    cfg = private_json(config_path)
    expected = cfg.get('authorized_target', '卧底不追高')
    pinned = cfg.get('authorized_chat_id')
    if target != expected and (not pinned or target != pinned):
        raise ValueError('unauthorized target')
    if pinned is not None and (not isinstance(pinned, str) or not pinned.endswith('@chatroom')):
        raise ValueError('invalid authorized chat ID')
    db_dir = Path(cfg['db_dir']).resolve(strict=True)
    keys = private_json(cfg['keys_file'])
    keys = keys.get('keys', keys)
    if not isinstance(keys, dict): raise ValueError('invalid key manifest')
    # Discover on-disk shards, not just keys: a missing key must fail closed.
    databases = sorted(p.relative_to(db_dir).as_posix() for p in (db_dir / 'message').glob('message_*.db') if re.fullmatch(r'message_\d+\.db', p.name))
    if not databases: raise ValueError('no message databases')
    rels = ['contact/contact.db'] + databases
    resolved_keys = {rel: _key(keys, rel) for rel in rels}
    for rel in rels:
        source = db_dir / rel
        if source.is_symlink() or not source.resolve().is_relative_to(db_dir): raise ValueError('database path escaped account')
    account = hashlib.sha256(str(db_dir).encode()).hexdigest()
    cp = private_json(cursor_path) if Path(cursor_path).exists() else None
    if cp is not None and (not isinstance(cp, dict) or cp.get('account') != account):
        raise ValueError('checkpoint account mismatch')
    cache = Path(cfg['cache_dir'])
    if not cache.is_absolute(): raise ValueError('cache_dir must be absolute')
    private_dir(cache)
    account_cache = cache / account
    private_dir(account_cache)
    # umask also protects any SHM native SQLite creates in the snapshot directory.
    old_umask = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(prefix='history-', dir=account_cache) as temp:
            temp = Path(temp)
            copies = {rel: snapshot(db_dir / rel, temp / rel) for rel in rels}
            salts = {}
            for rel in databases:
                with copies[rel].open('rb') as stream: salt = stream.read(16)
                if len(salt) != 16 or salt == b'SQLite format 3\x00': raise ValueError('expected encrypted database')
                salts[Path(rel).name] = salt.hex()
            with ReadonlyCipher(copies['contact/contact.db'], resolved_keys['contact/contact.db']) as conn:
                contacts = conn.execute('SELECT username, nick_name, remark FROM contact').fetchall()
            names = {u: remark or nick or u for u, nick, remark in contacts}
            if pinned:
                chat = pinned
                if chat not in names: raise ValueError('authorized chat not present in contacts')
            else:
                matches = {u for u, nick, remark in contacts if u.endswith('@chatroom') and expected in (u, nick, remark)}
                if len(matches) != 1: raise ValueError('target must exactly match one group')
                chat = matches.pop()
            cursors = {Path(rel).name: 0 for rel in databases}
            if cp is not None:
                if cp.get('chat') != chat: raise ValueError('checkpoint chat mismatch')
                prior_salts, prior_cursor = cp.get('salts'), cp.get('cursor')
                if not isinstance(prior_salts, dict) or not isinstance(prior_cursor, dict) or set(prior_salts) != set(prior_cursor):
                    raise ValueError('invalid checkpoint maps')
                for rel, value in prior_cursor.items():
                    if rel not in salts or prior_salts[rel] != salts[rel]: raise ValueError('checkpoint salt/database mismatch')
                    if type(value) is not int or value < 0: raise ValueError('invalid checkpoint cursor')
                    cursors[rel] = value
            table = 'Msg_' + hashlib.md5(chat.encode()).hexdigest()
            rows, has_more = [], False
            for rel in databases:
                database = Path(rel).name
                with ReadonlyCipher(copies[rel], resolved_keys[rel]) as conn:
                    records, more = collect_structured_history(conn, table, database, cursors[database], limit - len(rows), {'username': chat, 'display_name': names[chat]}, names)
                rows.extend(records)
                if records: cursors[database] = records[-1]['local_id']
                has_more = has_more or more
            return {'schema': 'aichat.history.v1', 'target': expected, 'account_fingerprint': account, 'chat_id': chat, 'rows': rows, 'checkpoint': {'account': account, 'chat': chat, 'cursor': cursors, 'salts': salts}, 'has_more': has_more}
    finally:
        os.umask(old_umask)
