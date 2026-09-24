"""Private local capture store. Source identities and exact send times stay unknown."""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import unicodedata
import uuid
from datetime import datetime, timezone

TARGET = '卧底不追高'


def now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def target_title(title):
    return bool(re.fullmatch(re.escape(TARGET) + r'\s*(?:\(\d+\))?',
                             unicodedata.normalize('NFKC', str(title)).strip()))


def signature(message):
    return (message['author'], message['kind'], message['text'])


def compatible(a, b):
    return signature(a) == signature(b) and not (
        a.get('timeLabel') and b.get('timeLabel') and a['timeLabel'] != b['timeLabel'])


class CaptureStore:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.path.parent, 0o700)
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(fd)
        os.chmod(self.path, 0o600)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS members(id TEXT PRIMARY KEY,name TEXT UNIQUE NOT NULL);
            CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY,created_at TEXT NOT NULL,gap_before INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS messages(
                id TEXT PRIMARY KEY,segment_id INTEGER NOT NULL REFERENCES segments(id),position INTEGER NOT NULL,
                member_id TEXT NOT NULL REFERENCES members(id),author TEXT NOT NULL,text TEXT NOT NULL,
                kind TEXT NOT NULL,confidence REAL NOT NULL,time_label TEXT,captured_at TEXT NOT NULL,
                UNIQUE(segment_id,position));
            CREATE TABLE IF NOT EXISTS pages(digest TEXT PRIMARY KEY,observed_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS counters(key TEXT PRIMARY KEY,value INTEGER NOT NULL);
            INSERT OR IGNORE INTO counters VALUES('observations',0),('duplicates',0);
        ''')
        self.db.commit()
        self.closed = False

    def close(self):
        with self.lock:
            if not self.closed:
                self.db.close()
                self.closed = True

    def _rows(self, segment_id):
        return [self._message(r) for r in self.db.execute(
            'SELECT * FROM messages WHERE segment_id=? ORDER BY position', (segment_id,))]

    @staticmethod
    def _message(row):
        return dict(id=row['id'], memberId=row['member_id'], author=row['author'],
                    text=row['text'], kind=row['kind'], confidence=row['confidence'],
                    timeLabel=row['time_label'], capturedAt=row['captured_at'],
                    sourceMessageId=None, sentAt=None, position=row['position'])

    def ingest(self, frame):
        if not target_title(frame.get('title')):
            raise ValueError('Target group does not match')
        incoming = frame.get('messages', [])
        if not isinstance(incoming, list) or not 0 < len(incoming) <= 150:
            raise ValueError('No complete visible messages')
        normalized = []
        for item in incoming:
            author = str(item.get('author', '')).strip()
            text = str(item.get('text') or '').strip()
            kind = item.get('kind', 'text')
            time_label = item.get('timeLabel')
            if not author or len(author) > 100 or len(text) > 12000 or kind not in ('text', 'media'):
                raise ValueError('Invalid message')
            if kind == 'text' and not text:
                raise ValueError('Empty text message')
            if time_label is not None and (not isinstance(time_label, str) or len(time_label) > 80):
                raise ValueError('Invalid visible time')
            normalized.append(dict(author=author, text=text, kind=kind,
                                   confidence=max(0, min(1, float(item.get('confidence', 0)))),
                                   timeLabel=time_label))
        incoming = normalized
        digest = hashlib.sha256(json.dumps(
            [(signature(m), m['timeLabel']) for m in incoming], ensure_ascii=False).encode()).hexdigest()
        captured = frame.get('capturedAt') or now()
        if not isinstance(captured, str) or datetime.fromisoformat(captured).tzinfo is None:
            raise ValueError('Capture time must include a timezone')
        with self.lock, self.db:
            self.db.execute("UPDATE counters SET value=value+1 WHERE key='observations'")
            previous = self.db.execute("SELECT value FROM metadata WHERE key='last_digest'").fetchone()
            seen_before = self.db.execute('SELECT 1 FROM pages WHERE digest=?', (digest,)).fetchone() is not None
            if previous and previous['value'] == digest:
                self.db.execute("UPDATE counters SET value=value+1 WHERE key='duplicates'")
                return dict(added=0, repeated=True)
            # Find a single unambiguous contiguous alignment. A pair of identical
            # generic utterances is not sufficient to establish an anchor.
            candidates = {}
            for segment in self.db.execute('SELECT id FROM segments ORDER BY id DESC'):
                sid = segment['id']
                old = self._rows(sid)
                for i in range(len(old)):
                    for j in range(len(incoming)):
                        if not compatible(old[i], incoming[j]):
                            continue
                        offset = i - j
                        lo, hi = max(0, -offset), min(len(incoming), len(old) - offset)
                        if hi - lo < 2:
                            continue
                        overlap = incoming[lo:hi]
                        if len(set(signature(m) for m in overlap)) < 2:
                            continue
                        if all(compatible(old[k + offset], incoming[k]) for k in range(lo, hi)):
                            candidates[(sid, offset)] = (hi - lo, old)
            chosen = None
            if len(candidates) == 1 and not seen_before:
                chosen = next(iter(candidates))
            added = 0
            if chosen:
                sid, offset = chosen
                old = candidates[chosen][1]
                first_position = old[0]['position']
            else:
                count = self.db.execute('SELECT count(*) FROM segments').fetchone()[0]
                sid = self.db.execute('INSERT INTO segments(created_at,gap_before) VALUES(?,?)',
                                      (captured, int(count > 0))).lastrowid
                old, offset, first_position = [], 0, 0
            for index, item in enumerate(incoming):
                old_index = index + offset
                if chosen and 0 <= old_index < len(old):
                    if item['timeLabel'] and not old[old_index]['timeLabel']:
                        self.db.execute('UPDATE messages SET time_label=? WHERE id=?',
                                        (item['timeLabel'], old[old_index]['id']))
                    continue
                member = self.db.execute('SELECT id FROM members WHERE name=?', (item['author'],)).fetchone()
                mid = member['id'] if member else str(uuid.uuid4())
                if not member:
                    self.db.execute('INSERT INTO members(id,name) VALUES(?,?)', (mid, item['author']))
                position = first_position + old_index
                self.db.execute('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?)',
                                (str(uuid.uuid4()), sid, position, mid, item['author'], item['text'],
                                 item['kind'], item['confidence'], item['timeLabel'], captured))
                added += 1
            self.db.execute('INSERT OR REPLACE INTO pages VALUES(?,?)', (digest, captured))
            self.db.execute("INSERT OR REPLACE INTO metadata VALUES('last_digest',?)", (digest,))
            if added == 0:
                self.db.execute("UPDATE counters SET value=value+1 WHERE key='duplicates'")
            return dict(added=added, repeated=added == 0, segmentId=sid)

    def state(self):
        with self.lock:
            members = [dict(id=r['id'], name=r['name'], identityConfidence='display_name_only')
                       for r in self.db.execute('SELECT * FROM members ORDER BY rowid')]
            segments = [dict(id=r['id'], createdAt=r['created_at'], gapBefore=bool(r['gap_before']),
                             messages=self._rows(r['id']))
                        for r in self.db.execute('SELECT * FROM segments ORDER BY id')]
            count = sum(len(s['messages']) for s in segments)
            stats = {r['key']: r['value'] for r in self.db.execute('SELECT * FROM counters')}
            return dict(group=dict(name=TARGET, memberCount=len(members), messageCount=count),
                        members=members, segments=segments, stats=stats,
                        coverage=dict(complete=False, description=(
                            '仅覆盖采集开启时看到的页面；有重叠的页面会对齐。分开的片段可能有遗漏或重叠。'
                            '昵称为临时成员标识，时间标签不代表每条消息的精确时间。')))
