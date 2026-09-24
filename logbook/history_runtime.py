"""Load the history plugin with the existing dsh-java runtime, without model plugins."""
import hashlib,json,os,signal,subprocess,time
from pathlib import Path
from .store import private_write
ROOT=Path(__file__).resolve().parents[1]
WORKSPACE=ROOT.parents[1]
BASE=Path(os.environ.get('DSH_JAVA_HOME','/Users/hou/Documents/Codex/projects/dsh-java'))

def plugin_config(data=None):
    data=Path(data or WORKSPACE/'work/chatlog').resolve()
    source=json.loads((data/'database-source.json').read_text())
    state=data/'history-plugin';state.mkdir(mode=0o700,exist_ok=True)
    config=state/'cli-config.json'
    private_write(config,json.dumps(dict(db_dir=source['dbRoot'],keys_file=source['keyFile'],cache_dir=str(data/'history-cache'),authorized_target='卧底不追高'),ensure_ascii=False).encode())
    return dict(commandPrefix=[str(ROOT/'wechat-cli/.venv/bin/python'),str(ROOT/'wechat-cli/entry.py')],cliConfig=str(config),keyFile=source['keyFile'],dataDir=str(state),logbookUrl='http://127.0.0.1:48742',tokenFile=str(data/'service.token'),intervalSeconds=3,timeoutSeconds=30)

def plugin_row(config):
    return dict(id='wechat-history',name='wechat-history-plugin',artifact=str(ROOT/'plugins/wechat-history/target/wechat-history-plugin.jar'),config=config)

def _owned(pid_file,bootstrap):
    try:
        pid=int(pid_file.read_text());command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True,stderr=subprocess.DEVNULL)
        return pid if '--dsh.bootstrap='+str(bootstrap) in command and str(BASE/'app-boot/target/dsh-java.jar') in command else None
    except (OSError,ValueError,subprocess.CalledProcessError):return None

def ensure_history_runtime():
    data=WORKSPACE/'work/chatlog';mode=data/'source-mode.json'
    if not mode.exists() or json.loads(mode.read_text()).get('source')!='wechat_cli_history':return
    config=plugin_config(data);state=Path(config['dataDir']);bootstrap=state/'bootstrap.json';pid_file=state/'runtime.pid';digest_file=state/'artifact.sha256'
    row=plugin_row(config);artifact=Path(row['artifact']);boot=BASE/'app-boot/target/dsh-java.jar'
    for path in (artifact,boot,*map(Path,config['commandPrefix'])):
        if not path.is_file():raise RuntimeError('history 插件构建产物尚未就绪。')
    digest=hashlib.sha256(artifact.read_bytes()+json.dumps(config,sort_keys=True).encode()).hexdigest()
    pid=_owned(pid_file,bootstrap)
    if pid and digest_file.exists() and digest_file.read_text()==digest:return
    if pid:
        os.kill(pid,signal.SIGTERM);deadline=time.monotonic()+12
        while _owned(pid_file,bootstrap) and time.monotonic()<deadline:time.sleep(.1)
        if _owned(pid_file,bootstrap):raise RuntimeError('旧 history 插件尚未完成资源释放。')
    private_write(bootstrap,json.dumps(dict(plugins=[row]),ensure_ascii=False,indent=2).encode())
    java=Path('/opt/homebrew/opt/openjdk@17/bin/java')
    if not java.is_file():raise RuntimeError('缺少 Java 17。')
    log_file=state/'runtime.log'
    with log_file.open('wb') as log:
        log_file.chmod(0o600)
        child=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8','-jar',str(boot),'--dsh.bootstrap='+str(bootstrap),'--spring.main.web-application-type=none'],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    private_write(pid_file,str(child.pid).encode())
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        if child.poll() is not None:raise RuntimeError('dsh-java history 插件启动失败，请查看私有 runtime.log。')
        if 'F01 bootstrap ready: configured=1, active=1' in log_file.read_text(errors='replace'):
            private_write(digest_file,digest.encode());return
        time.sleep(.2)
    child.terminate()
    try:child.wait(timeout=10)
    except subprocess.TimeoutExpired:pass
    raise RuntimeError('dsh-java history 插件未在限定时间内就绪。')
