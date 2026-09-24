"""On-device capture loop. No UI control, model calls, or remote transports."""
import hashlib
import json
from pathlib import Path
import re
import select
import subprocess
import threading
import time

from store import TARGET, now, target_title

TIME = re.compile(r'^(?:(?:昨天|前天|今天|星期[一二三四五六日天]|周[一二三四五六日天]|\d{4}年\d{1,2}月\d{1,2}日|\d{1,2}月\d{1,2}日)\s*)?\d{1,2}:\d{2}$')
IGNORED = re.compile(r'^(?:\d+\s*条新消息|查看更多消息|以下是新消息|发送)$')


def parse_frame(raw):
    if raw.get('status') != 'ok' or not target_title(raw.get('title')):
        return None
    left, top, bottom = float(raw['chatLeft']), float(raw['chatTop']), float(raw['chatBottom'])
    width = float(raw['width'])
    messages, current, time_label = [], None, None

    def finish(end):
        if current is None or current['clipped']:
            return
        if current['parts']:
            messages.append(dict(author=current['author'], text='\n'.join(current['parts']), kind='text',
                                 confidence=min(current['scores']), timeLabel=current['time']))
        elif end - current['y'] >= 65:
            messages.append(dict(author=current['author'], text='', kind='media',
                                 confidence=current['scores'][0], timeLabel=current['time']))

    for line in sorted(raw.get('lines', []), key=lambda x: (round(float(x['y']) / 4), float(x['x']))):
        text = str(line.get('text', '')).strip()
        x, y = float(line['x']), float(line['y'])
        h = float(line['height'])
        if not text or x < left or IGNORED.fullmatch(text):
            continue
        if y < top + 2:
            continue
        if y + h > bottom - 3:
            if not line.get('bubble') and left + 42 <= x <= left + 79:
                finish(y)
                current = None
            elif current:
                current['clipped'] = True
            continue
        if TIME.fullmatch(text) and not line.get('bubble'):
            finish(y)
            current = None
            time_label = text
            continue
        # WeChat places sender labels immediately after the avatar column;
        # text within bubbles is indented further. Unknown image OCR is ignored.
        sender = (not line.get('bubble') and left + 42 <= x <= left + 79
                  and h <= 19 and len(text) <= 50)
        if sender:
            finish(y)
            current = dict(author=text, parts=[], scores=[float(line.get('confidence', 0))],
                           y=y, time=time_label, clipped=False)
        elif line.get('bubble'):
            outgoing = bool(line.get('outgoing', x > left + (width - left) * 0.52))
            if outgoing and current is not None:
                separate = (current['author'] != '我（窗口右侧）'
                            or y - current.get('lastBottom', current['y']) > 16)
                if separate:
                    finish(y)
                    current = None
            elif not outgoing and current is not None and current['author'] == '我（窗口右侧）':
                finish(y)
                current = None
            if current is None:
                # Fully visible self bubbles have no nickname on the right.
                if outgoing and y > top + 18:
                    current = dict(author='我（窗口右侧）', parts=[], scores=[], y=y,
                                   time=time_label, clipped=False)
                else:
                    continue
            current['parts'].append(text)
            current['scores'].append(float(line.get('confidence', 0)))
            current['lastBottom'] = y + h
    finish(bottom)
    return dict(title=raw['title'], windowId=raw.get('windowId'), messages=messages)


class StablePages:
    def __init__(self):
        self.reset()

    def reset(self):
        self.previous = None

    def accept(self, page):
        key = json.dumps([page.get('windowId'), [
            (m['author'], m['text'], m['kind'], m.get('timeLabel')) for m in page['messages']]],
            ensure_ascii=False)
        digest = hashlib.sha256(key.encode()).hexdigest()
        stable = digest == self.previous
        self.previous = digest
        return stable


DETAILS = {
    'paused': '采集已暂停，已保存的消息仍可查看。',
    'starting': '正在连接本机微信窗口…',
    'capturing': '正在采集这个群。你可以直接在微信中滚动，页面停稳后自动保存。',
    'stabilizing': '已看到目标群，等待页面停稳后保存。',
    'waiting_content': '已看到目标群，当前没有可确认完整归属的消息。',
    'other_chat': '当前不是指定群，已暂停入库；回到卧底不追高后自动继续。',
    'wechat_not_running': '等待微信运行并打开卧底不追高。',
    'window_unavailable': '等待微信聊天窗口可见；最小化或锁屏时不采集。',
    'permission_required': '本机采集需要 macOS 屏幕录制权限。请在系统设置 → 隐私与安全性 → 屏幕录制中授权启动爱聊的应用。',
    'capture_error': '本机采集暂时未成功，正在重试。',
    'helper_missing': '本机采集组件尚未构建，请运行爱聊的微信采集启动器。',
}


class Collector:
    def __init__(self, store, helper, interval=1.5):
        self.store, self.helper, self.interval = store, Path(helper), interval
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.wake = threading.Event()
        self.stable = StablePages()
        self.process = None
        self.enabled = False
        self.generation = 0
        self.status = 'paused'
        self.last_captured = self.last_attempt = None
        self.thread = threading.Thread(target=self._loop, name='wechat-local-capture', daemon=True)
        self.thread.start()

    def state(self):
        with self.lock:
            return dict(enabled=self.enabled, status=self.status,
                        detail=DETAILS.get(self.status, DETAILS['capture_error']),
                        lastCapturedAt=self.last_captured, lastAttemptAt=self.last_attempt)

    def start(self):
        with self.lock:
            if not self.enabled:
                self.enabled = True
                self.generation += 1
                self.status = 'starting'
                self.stable.reset()
        self.wake.set()

    def pause(self):
        with self.lock:
            self.enabled = False
            self.generation += 1
            self.status = 'paused'
            self.stable.reset()
        self.wake.set()

    def _close_helper(self):
        if self.process:
            process, self.process = self.process, None
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
            for stream in (process.stdin, process.stdout):
                if stream:
                    stream.close()

    def _capture(self):
        if not self.helper.is_file():
            return dict(status='helper_missing')
        if self.process is None or self.process.poll() is not None:
            self._close_helper()
            self.process = subprocess.Popen([str(self.helper), '--target', TARGET], stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                            bufsize=0)
        self.process.stdin.write(b'capture\n')
        self.process.stdin.flush()
        if not select.select([self.process.stdout], [], [], 20)[0]:
            raise TimeoutError('Capture timed out')
        line = self.process.stdout.readline(2_000_001)
        if not line or len(line) > 2_000_000:
            raise ValueError('Invalid capture response')
        return json.loads(line)

    def _loop(self):
        while not self.stop_event.is_set():
            with self.lock:
                enabled, generation = self.enabled, self.generation
            if enabled:
                try:
                    with self.lock:
                        self.last_attempt = now()
                    raw = self._capture()
                    page = parse_frame(raw)
                    with self.lock:
                        if self.enabled and generation == self.generation:
                            if page is None:
                                self.status = raw.get('status', 'capture_error')
                                if self.status == 'ok':
                                    self.status = 'other_chat'
                                self.stable.reset()
                            elif not page['messages']:
                                self.status = 'waiting_content'
                                self.stable.reset()
                            elif not self.stable.accept(page):
                                self.status = 'stabilizing'
                            else:
                                self.store.ingest(page)
                                self.last_captured = now()
                                self.status = 'capturing'
                except Exception:
                    self._close_helper()
                    with self.lock:
                        if self.enabled and generation == self.generation:
                            self.status = 'capture_error'
                            self.stable.reset()
            self.wake.wait(self.interval)
            self.wake.clear()
        self._close_helper()

    def close(self):
        self.pause()
        self.stop_event.set()
        self.wake.set()
        self.thread.join(timeout=23)
