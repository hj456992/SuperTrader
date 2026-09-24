#!/usr/bin/env python3
"""Start and verify structured-log services; safely replace only our own sidecar."""
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
WORKSPACE=ROOT.parents[1]


def ready(port,identity):
    c=http.client.HTTPConnection('127.0.0.1',port,timeout=1)
    try:
        c.request('GET','/health');r=c.getresponse();body=r.read(4096)
        if r.status!=200:return False
        value=json.loads(body)
        return isinstance(value,dict) and value.get('app')==identity and value.get('version')==1 and (identity!='AiChatViewer' or (value.get('mode')=='structured-log-readonly' and 'wechat_cli_history' in value.get('logSources',[]))) and (identity!='AiChatLog' or value.get('acquisition')=='history-plugin-and-export')
    except (OSError,ValueError,http.client.HTTPException):return False
    finally:c.close()


def occupied(port):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.4):return True
    except OSError:return False


def stop_owned(pid_file,script):
    try:
        pid=int(pid_file.read_text())
        command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True,stderr=subprocess.DEVNULL).strip()
        # Only a process whose full argv ends in this exact workspace-owned script.
        if not command.endswith(' '+str(script.resolve())):return False
        os.kill(pid,signal.SIGTERM)
        return True
    except (OSError,ValueError,subprocess.CalledProcessError):return False


def ensure_service(port,identity,script,pid_file,log_file,timeout=10):
    script=Path(script).resolve();pid_file=Path(pid_file);log_file=Path(log_file)
    if ready(port,identity):return
    if occupied(port):
        if not stop_owned(pid_file,script):
            raise RuntimeError(f'端口 {port} 被其他程序占用，未替换该程序。')
        deadline=time.monotonic()+5
        while occupied(port) and time.monotonic()<deadline:time.sleep(.1)
        if occupied(port):raise RuntimeError(f'旧本地服务尚未退出：{port}。')
    pid_file.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    with log_file.open('ab') as log:
        os.chmod(log_file,0o600)
        child=subprocess.Popen([sys.executable,str(script)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,cwd=WORKSPACE,start_new_session=True)
    pid_file.write_text(str(child.pid));pid_file.chmod(0o600)
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if child.poll() is not None:raise RuntimeError(f'日志服务启动后退出（{child.returncode}），查看本地 service.log。')
        if ready(port,identity):return
        time.sleep(.15)
    child.terminate()
    try:child.wait(timeout=3)
    except subprocess.TimeoutExpired:child.kill();child.wait()
    raise RuntimeError(f'服务 {port} 未在限定时间内就绪。')


def main():
    os.umask(0o077)
    try:
        if not (ROOT/'web/dist/logbook.html').exists():subprocess.run(['npm','--prefix',str(ROOT/'web'),'run','build'],check=True)
        for port,identity,script,directory in [(48742,'AiChatLog','logbook/server.py','chatlog'),(48741,'AiChatViewer','wechat/server.py','wechat-capture')]:
            data=WORKSPACE/'work'/directory
            ensure_service(port,identity,ROOT/script,data/'service.pid',data/'service.log')
        sys.path.insert(0,str(ROOT))
        from logbook.history_runtime import ensure_history_runtime
        ensure_history_runtime()
    except (OSError,RuntimeError,subprocess.CalledProcessError) as error:
        print('启动未完成：'+str(error),file=sys.stderr);return 1
    print('聊天日志已就绪：http://127.0.0.1:48742/')
    print('爱聊阅读已就绪：http://127.0.0.1:48741/wechat.html')
    return 0

if __name__=='__main__':raise SystemExit(main())
