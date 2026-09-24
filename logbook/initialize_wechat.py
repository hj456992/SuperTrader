#!/usr/bin/python3
"""Interactive, local-only admin bootstrap. Never print keys or message bodies."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import plistlib
import pwd
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
APP = Path('/Applications/WeChat.app')
STATE = ROOT / 'work/wechat-db-bootstrap'


def record_status(stage, detail=''):
    """Persist fixed diagnostic wording, never native stdout, keys or messages."""
    user_name = os.environ.get('SUDO_USER')
    user = pwd.getpwnam(user_name) if user_name else pwd.getpwuid(os.getuid())
    payload = json.dumps({'stage': stage, 'detail': detail, 'updatedAt': time.time()}, ensure_ascii=False).encode()
    write_private(STATE / 'initialization-status.json', payload, user.pw_uid, user.pw_gid)


def run(argv, **kwargs):
    return subprocess.run([str(x) for x in argv], capture_output=True, **kwargs)


def verify_key(key, page):
    if len(key) != 32 or len(page) != 4096:
        return False
    mac_key = hashlib.pbkdf2_hmac('sha512', key, bytes(x ^ 0x3a for x in page[:16]), 2, 32)
    expected = hmac.new(mac_key, page[16:4032] + b'\x01\x00\x00\x00', hashlib.sha512).digest()
    return hmac.compare_digest(expected, page[4032:])


def write_private(path, value, uid, gid):
    fd, temporary = tempfile.mkstemp(dir=str(path.parent))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.chown(temporary, uid, gid)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    if os.geteuid() != 0 or not os.environ.get('SUDO_USER'):
        raise RuntimeError('请通过“初始化微信读取.command”在终端运行。')
    user = pwd.getpwnam(os.environ['SUDO_USER'])
    if user.pw_uid == 0 or ROOT.stat().st_uid != user.pw_uid:
        raise RuntimeError('运行账号与项目所有者不一致。')
    os.umask(0o077)
    source = json.loads((ROOT / 'work/chatlog/database-source.json').read_text())
    db_root = Path(source['dbRoot']).resolve(strict=True)
    key_file = Path(source['keyFile']).resolve()
    if key_file != STATE / 'keys.json':
        raise RuntimeError('密钥路径与当前项目配置不一致。')
    if key_file.is_file():
        print('本机已有密钥，未重复修改微信。请直接打开爱聊查看同步状态。', flush=True)
        record_status('keys_already_present')
        return
    required = [db_root / 'contact/contact.db'] + sorted((db_root / 'message').glob('message_[0-9]*.db'))
    if len(required) < 2:
        raise RuntimeError('未找到目标账号数据库。')
    record_status('waiting_for_quit')
    print('本操作会备份并重新签名微信，添加调试读取权限；不修改聊天数据库。', flush=True)
    input('请先用微信菜单“退出微信”，完全退出后回到这里按回车：')
    if run(['/usr/bin/pgrep', '-x', 'WeChat']).returncode == 0:
        raise RuntimeError('微信仍在运行，未修改应用。请完全退出后重新运行。')
    print('正在校验原版微信签名（应用约1.4GB，可能需要数分钟），请稍候…', flush=True)
    record_status('verifying_original')
    if run(['/usr/bin/codesign', '--verify', '--deep', '--strict', APP]).returncode:
        raise RuntimeError('当前微信签名校验失败，未修改。')
    ent = run(['/usr/bin/codesign', '-d', '--entitlements', ':-', APP])
    if ent.returncode or not ent.stdout:
        raise RuntimeError('无法读取原微信权限，未修改。')
    entitlements = plistlib.loads(ent.stdout)
    backup = STATE / ('WeChat-original-' + time.strftime('%Y%m%d-%H%M%S') + '.backup')
    print('正在备份原版微信…', flush=True)
    record_status('backing_up')
    if backup.exists() or run(['/usr/bin/ditto', APP, backup]).returncode:
        raise RuntimeError('原版备份未完成，未修改微信。')
    print('备份已复制，正在校验备份完整性，请稍候…', flush=True)
    record_status('verifying_backup')
    if run(['/usr/bin/codesign', '--verify', '--deep', '--strict', backup]).returncode:
        raise RuntimeError('备份签名校验失败，未修改微信。')
    print('备份校验通过，正在配置读取权限…', flush=True)
    record_status('signing')
    entitlements['com.apple.security.get-task-allow'] = True
    with tempfile.TemporaryDirectory(prefix='aichat-sign-') as temp:
        ent_file = Path(temp) / 'entitlements.plist'
        ent_file.write_bytes(plistlib.dumps(entitlements))
        signed = run(['/usr/bin/codesign', '--force', '--sign', '-', '--entitlements', ent_file, APP])
    if signed.returncode or run(['/usr/bin/codesign', '--verify', '--deep', '--strict', APP]).returncode:
        raise RuntimeError('签名未完成。原版备份保存在：' + str(backup))
    print('读取权限已配置。原版备份：' + str(backup), flush=True)
    record_status('waiting_for_login')
    input('请重新打开微信并登录，打开“卧底不追高”后，回到这里按回车：')
    pids = run(['/usr/bin/pgrep', '-x', 'WeChat']).stdout.decode().split()
    if len(pids) != 1:
        raise RuntimeError('需要恰好一个正在运行的微信。')
    command = run(['/bin/ps', '-p', pids[0], '-o', 'comm=']).stdout.decode().strip()
    if command != str(APP / 'Contents/MacOS/WeChat'):
        raise RuntimeError('当前运行的不是 /Applications 中的微信。')
    scanner = STATE / 'wechat-cli-scanner'
    print('正在提取并校验本机数据库密钥，终端不会显示密钥或消息正文…', flush=True)
    record_status('extracting_keys')
    with tempfile.TemporaryDirectory(prefix='aichat-keys-') as temp:
        scanned = run([scanner, pids[0]], cwd=temp, timeout=120)
        key_result = Path(temp) / 'all_keys.json'
        if scanned.returncode or not key_result.is_file():
            detail = 'macOS仍拒绝进程读取。' if b'task_for_pid' in scanned.stderr else '扫描程序未成功返回。'
            raise RuntimeError(detail + '尚未取得密钥。')
        candidates = json.loads(key_result.read_text())
        verified = {}
        for path in required:
            rel = path.relative_to(db_root).as_posix()
            value = candidates.get(rel, {}).get('enc_key', '')
            try:
                key = bytes.fromhex(value)
            except ValueError:
                key = b''
            with path.open('rb') as stream:
                page = stream.read(4096)
            if not verify_key(key, page):
                raise RuntimeError('所需数据库密钥缺失或校验失败，未启用采集。')
            verified[rel] = {'enc_key': key.hex()}
    write_private(key_file, json.dumps({'keys': verified}).encode(), user.pw_uid, user.pw_gid)
    record_status('keys_verified')
    print('密钥校验通过并已私密保存。爱聊插件会自动开始读取。', flush=True)
    print('请回到爱聊检查同步状态；若仍报错，把状态告诉我即可，无需发送密钥。')


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        record_status('cancelled')
        print('\n操作已停止。')
        raise SystemExit(1)
    except Exception as error:
        # Only explicit fixed errors above are useful to users. Native output is withheld.
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        try:
            record_status('failed', detail)
        except Exception:
            pass
        print('初始化未完成：' + detail)
        raise SystemExit(1)
