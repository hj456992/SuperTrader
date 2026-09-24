#!/usr/bin/env python3
"""On-demand WeChat catalog and selected conversation, encrypted snapshots only.

No global cache, plaintext database export, model calls, or writes to source DBs.
Identity is deliberately unknown: another sender is never assumed to be self.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / 'wechat-cli'))
from wechat_cli.core.structured_history import private_json, snapshot, _key
from wechat_cli.core.readonly_cipher import ReadonlyCipher
from wechat_cli.core.messages import _format_message_text
import zstandard as zstd

CATALOG_LIMIT = 10000
MESSAGE_LIMIT = 500
TEXT_LIMIT = 4000
CONTENT_BYTES = 32768
OUTPUT_BYTES = 2_000_000


def message_table(chat):
    return 'Msg_' + hashlib.md5(chat.encode('utf-8')).hexdigest()


def session_catalog(conn, names):
    """Query metadata only, including sessions with zero last timestamp."""
    total = int(conn.execute("SELECT COUNT(DISTINCT username) FROM SessionTable WHERE username IS NOT NULL AND username != ''").fetchone()[0])
    rows = conn.execute("SELECT username, MAX(last_timestamp) FROM SessionTable WHERE username IS NOT NULL AND username != '' GROUP BY username ORDER BY MAX(last_timestamp) DESC, username ASC LIMIT ?", (CATALOG_LIMIT,)).fetchall()
    chats = []
    size = 0
    for username, _ in rows:
        if len(username) > 512:
            continue
        chat = {'id': username, 'title': str(names.get(username) or username)[:512], 'isGroup': username.endswith('@chatroom')}
        size += len(json.dumps(chat, ensure_ascii=False).encode('utf-8'))
        if size > OUTPUT_BYTES - 2000:
            break
        chats.append(chat)
    return chats, total


def _decoded(raw, compression):
    if compression == 4:
        params = zstd.get_frame_parameters(raw)
        if params.content_size not in (zstd.CONTENTSIZE_UNKNOWN, zstd.CONTENTSIZE_ERROR) and params.content_size > 65536:
            return '[消息解压后过长，未展开]', True
        with zstd.ZstdDecompressor().stream_reader(raw) as stream:
            decoded = stream.read(65537)
        if len(decoded) > 65536:
            return '[消息解压后过长，未展开]', True
        raw = decoded
    return raw.decode('utf-8', errors='replace'), False


def chat_messages(conn, chat, names, account, shard):
    """Only the selected chat table is queried; Name2Id resolves stable senders."""
    table = message_table(chat)
    if not conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
        return [], 0
    total = int(conn.execute(f'SELECT COUNT(*) FROM [{table}]').fetchone()[0])
    # Cap each cell before hex conversion; malformed/oversized content is disclosed.
    records = conn.execute(f'''SELECT local_id, server_id, local_type, create_time,
        real_sender_id, hex(CASE WHEN length(message_content) <= ? THEN message_content ELSE NULL END),
        WCDB_CT_message_content, message_content IS NULL, length(message_content) > ?
        FROM [{table}] ORDER BY create_time DESC, local_id DESC LIMIT ?''',
        (CONTENT_BYTES, CONTENT_BYTES, MESSAGE_LIMIT)).fetchall()
    sender_rows = conn.execute('SELECT rowid, user_name FROM Name2Id').fetchall()
    id_to_username = {int(rowid): username for rowid, username in sender_rows if username}
    known_senders = set(id_to_username.values()) | set(names)
    result = []
    is_group = chat.endswith('@chatroom')
    for local, server, kind, created, real_sender, content_hex, compression, is_null, too_large in records:
        local, kind, created = int(local), int(kind or 0), int(created or 0)
        truncated = bool(int(too_large or 0))
        if truncated:
            content = '[消息原文过长，未展开]'
        elif int(is_null):
            content = ''
        else:
            try:
                content, truncated = _decoded(bytes.fromhex(content_hex or ''), int(compression or 0))
            except (ValueError, zstd.ZstdError):
                content, truncated = '[消息内容无法解析]', True
        sender_prefix, text = _format_message_text(local, kind, content, is_group,
            chat, names.get(chat, chat), names, lambda user, mapping: mapping.get(user, user), resolve_media=False)
        member = id_to_username.get(int(real_sender)) if real_sender is not None else None
        if is_group and member == chat:
            member = None
        if not member and is_group and sender_prefix in known_senders and sender_prefix != chat:
            member = sender_prefix
        if not member and is_group and sender_prefix and (kind & 0xFFFFFFFF) == 1:
            text = content
        member = member or ''
        if len(member) > 512:
            member = ''
        text = str(text or '')
        if len(text) > TEXT_LIMIT:
            text, truncated = text[:TEXT_LIMIT] + '\n[原文过长，已截取]', True
        # Server IDs dedup across shard moves; local fallback includes account/chat/shard.
        server = str(server or '0')
        identity = f'{account}\0{chat}\0server\0{server}' if server.isdigit() and int(server) > 0 else f'{account}\0{chat}\0{shard}\0{local}'
        stable_id = 'wx-' + hashlib.sha256(identity.encode('utf-8')).hexdigest()
        try:
            at = datetime.fromtimestamp(created, timezone.utc).isoformat().replace('+00:00', 'Z')
        except (ValueError, OverflowError, OSError):
            at = ''
        result.append({'id': stable_id, 'memberId': member,
            'memberName': str(names.get(member) or member or '未知成员')[:512],
            'text': text, 'at': at, 'isMe': False, '_time': created,
            '_local': local, '_shard': shard, '_truncated': truncated})
    return result, total


def newest_messages(messages, limit=MESSAGE_LIMIT):
    """Deterministic newest selection across shards, returned chronologically."""
    ordered = sorted(messages, key=lambda row: (row['_time'], row['_local'], row['_shard'], row['id']), reverse=True)
    selected, seen = [], set()
    for message in ordered:
        if message['id'] in seen:
            continue
        seen.add(message['id'])
        selected.append(message)
        if len(selected) == limit:
            break
    return list(reversed(selected))


def _source(config_path):
    config = private_json(config_path)
    root = Path(config['dbRoot']).resolve(strict=True)
    keys = private_json(config['keyFile'])
    keys = keys.get('keys', keys)
    if not root.is_dir() or not isinstance(keys, dict):
        raise ValueError('invalid configuration')
    return root, keys


def _open_copy(root, keys, rel, temp):
    source = root / rel
    if source.is_symlink() or not source.resolve().is_relative_to(root):
        raise ValueError('invalid database location')
    key = _key(keys, rel)
    copy = snapshot(source, temp / rel)
    return ReadonlyCipher(copy, key)


def message_catalog(conn, names):
    """Recover conversation IDs using schema metadata, never message previews."""
    tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%'").fetchall()}
    return [{'id': user, 'title': str(name or user)[:512], 'isGroup': user.endswith('@chatroom')}
            for user, name in names.items() if len(user) <= 512 and message_table(user) in tables]


def availability_detail(session_available, shard_total, readable, count):
    if session_available:
        detail = f'当前本机微信会话目录识别{count}个会话。'
    else:
        detail = f'从当前可读取的消息库识别{count}个会话；会话索引暂不可读，可能不完整。'
    if shard_total:
        detail += f'当前可读取{readable}/{shard_total}个消息分库。'
        if readable < shard_total:
            detail += f'另有{shard_total - readable}个消息分库暂不可读，可能不完整。'
    return detail


def run(action, chat=None, config_path=None):
    if action not in ('list', 'history'):
        raise ValueError('invalid action')
    if action == 'history' and (not isinstance(chat, str) or not chat or len(chat) > 512 or '\x00' in chat):
        raise ValueError('invalid selection')
    root, keys = _source(config_path or os.environ.get('GARDEN_WECHAT_SOURCE') or WORKSPACE / 'work/chatlog/database-source.json')
    old_umask = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(prefix='garden-wechat-') as directory:
            return _read_selected(root, keys, Path(directory), action, chat)
    finally:
        os.umask(old_umask)


def _read_selected(root, keys, temp, action, chat):
    account = hashlib.sha256(str(root).encode('utf-8')).hexdigest()
    # Contact names are catalog metadata; no message contents are queried here.
    with _open_copy(root, keys, 'contact/contact.db', temp) as conn:
        contact_total = int(conn.execute('SELECT COUNT(*) FROM contact').fetchone()[0])
        rows = conn.execute('SELECT username,nick_name,remark FROM contact LIMIT 100000').fetchall()
        names = {u: remark or nick or u for u, nick, remark in rows if u}
    session_available = False
    chats, total_chats = [], 0
    selected_in_sessions = False
    try:
        with _open_copy(root, keys, 'session/session.db', temp) as conn:
            if action == 'list':
                chats, total_chats = session_catalog(conn, names)
            else:
                selected_in_sessions = bool(conn.execute('SELECT 1 FROM SessionTable WHERE username=? LIMIT 1', (chat,)).fetchone())
            session_available = True
    except (ValueError, RuntimeError, OSError):
        # Existing config may authorize fewer DBs; don't discover/extract new keys.
        pass
    if action == 'history' and session_available and not selected_in_sessions:
        return {'status': 'error', 'detail': '所选会话不在当前微信会话目录中，请刷新列表。'}
    if action == 'list' and session_available:
        detail = f'当前本机微信账号的会话目录，共{total_chats}个会话，显示{len(chats)}个；未读取聊天正文。'
        if len(chats) < total_chats:
            detail += '目录受10000项及输出大小上限限制。'
        return {'status': 'ok', 'chats': chats, 'total': total_chats, 'scope': 'local-session-index', 'detail': detail}
    shards = sorted(path for path in (root / 'message').glob('message_*.db') if re.fullmatch(r'message_\d+\.db', path.name))
    if not shards or len(shards) > 256:
        return {'status': 'error', 'detail': '本机未找到可读取的微信消息分库。'}
    messages, total, readable, selected_tables = [], 0, 0, 0
    catalog_by_id = {}
    for path in shards:
        try:
            with _open_copy(root, keys, path.relative_to(root).as_posix(), temp) as conn:
                if action == 'list':
                    for row in message_catalog(conn, names):
                        catalog_by_id[row['id']] = row
                else:
                    # On metadata fallback, only catalog-mappable selected IDs are valid.
                    if not session_available and chat not in names:
                        continue
                    exists = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (message_table(chat),)).fetchone()
                    if exists:
                        rows, count = chat_messages(conn, chat, names, account, path.name)
                        messages = newest_messages(messages + rows)
                        total += count
                        selected_tables += 1
                readable += 1
        except (ValueError, RuntimeError, OSError):
            # This shard contributes no records, and availability is disclosed below.
            continue
    if readable == 0:
        return {'status': 'error', 'detail': '当前配置没有可读取的微信消息分库，请检查读取配置后刷新。'}
    if action == 'list':
        all_chats = sorted(catalog_by_id.values(), key=lambda row: (row['title'], row['id']))
        total_chats, chats, size = len(all_chats), [], 0
        for row in all_chats[:CATALOG_LIMIT]:
            size += len(json.dumps(row, ensure_ascii=False).encode('utf-8'))
            if size > OUTPUT_BYTES - 3000:
                break
            chats.append(row)
        detail = availability_detail(False, len(shards), readable, total_chats)
        detail += f'显示{len(chats)}个；仅匹配会话表名与联系人名称，未读取聊天正文。'
        if len(chats) < total_chats:
            detail += '目录受10000项及输出大小上限限制。'
        if contact_total > len(names):
            detail += '联系人名称元数据亦受读取上限限制。'
        return {'status': 'ok', 'chats': chats, 'total': total_chats, 'scope': 'local-readable-message-metadata', 'detail': detail}
    if not selected_tables or not messages:
        return {'status': 'error', 'detail': '所选会话没有可读取的近期消息。' + availability_detail(session_available, len(shards), readable, 1)}
    clipped = sum(bool(m['_truncated']) for m in messages)
    messages = [{key: value for key, value in m.items() if not key.startswith('_')} for m in messages]
    while messages and len(json.dumps(messages, ensure_ascii=False).encode('utf-8')) > OUTPUT_BYTES - 3000:
        messages.pop(0)
    detail = availability_detail(session_available, len(shards), readable, 1)
    detail += f'仅按需读取所选会话，返回最近{len(messages)}条（最多500条）；可读数据库记录共{total}条，可能含跨库重复。'
    if clipped:
        detail += f'{clipped}条内容过长或无法展开，已明确标注。'
    detail += '未读取其他会话正文；图片、语音等仅有类型提示。暂未自动确认哪位成员是你，请根据身份自行选择。'
    return {'status': 'ok', 'chat': {'id': chat, 'title': str(names.get(chat) or chat)[:512], 'isGroup': chat.endswith('@chatroom')},
        'messages': messages, 'total': total, 'identityKnown': False, 'detail': detail}


def main(args=None):
    args = list(sys.argv[1:] if args is None else args)
    try:
        if args == ['list']:
            result = run('list')
        elif len(args) == 3 and args[0] == 'history' and args[1] == '--chat':
            result = run('history', args[2])
        else:
            result = {'status': 'error', 'detail': '请选择list或history --chat 会话ID。'}
        encoded = json.dumps(result, ensure_ascii=False, separators=(',', ':'))
        if len(encoded.encode('utf-8')) > OUTPUT_BYTES:
            result = {'status': 'error', 'detail': '目录内容超过输出上限，请缩小范围后重试。'}
            encoded = json.dumps(result, ensure_ascii=False)
        print(encoded)
        return 0 if result['status'] == 'ok' else 1
    except Exception:
        print(json.dumps({'status': 'error', 'detail': '微信本机资料暂时无法读取；请确认已登录、资料配置可用后刷新重试。'}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    sys.exit(main())
