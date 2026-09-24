#!/usr/bin/env python3
"""在独立端口/数据库中装配 dsh-java 与花园插件；凭据不写盘、不输出。"""
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
BASE = pathlib.Path(os.environ.get('DSH_JAVA_HOME', '/Users/hou/Documents/Codex/projects/dsh-java'))
DB = 'garden_demo_20260922'
PORT = os.environ.get('GARDEN_PORT', '48740')
env = os.environ.copy()
if not env.get('DEEPSEEK_API_KEY'):
    sys.exit('请在启动进程环境中配置 DEEPSEEK_API_KEY；不要把密钥写入工程。')
if not env.get('GARDEN_DB_URL'):
    credentials = {}
    for line in (BASE / '.local/postgres.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            credentials[key.strip()] = value.strip().strip('"').strip("'")
    user = credentials['POSTGRES_USER']
    docker = shutil.which('docker') or '/opt/homebrew/bin/docker'
    query = [docker, 'exec', 'dsh-java-postgres', 'psql', '-U', user, '-d', 'postgres', '-Atqc']
    exists = subprocess.run(query + [f"SELECT 1 FROM pg_database WHERE datname='{DB}'"],check=True,capture_output=True,text=True).stdout.strip()
    if exists != '1':
        subprocess.run(query + [f'CREATE DATABASE {DB}'],check=True,capture_output=True)
    env['GARDEN_DB_URL'] = f'jdbc:postgresql://127.0.0.1:15440/{DB}'
    env['GARDEN_DB_USER'] = user
    env['GARDEN_DB_PASSWORD'] = credentials['POSTGRES_PASSWORD']
env['GARDEN_WEB'] = str(ROOT / 'web/dist')
env['GARDEN_PORT'] = PORT
runtime = ROOT / '.runtime'
runtime.mkdir(mode=0o700,exist_ok=True)
bootstrap = runtime / 'bootstrap.yml'
plugins = [('model-registry','dsh-java-model-registry',BASE / 'plugins/model-registry/target/model-registry.jar',False),('model-deepseek','dsh-java-model-deepseek',BASE / 'plugins/model-deepseek/target/model-deepseek.jar',True),('feishu-history','feishu-history-plugin',ROOT / 'plugins/feishu-history/target/feishu-history-plugin.jar',False),('garden-demo','garden-demo',ROOT / 'target/garden-demo.jar',True)]
lines = ['plugins:']
for id_, name, artifact, inject in plugins:
    if not artifact.is_file():
        sys.exit(f'缺少构建产物：{artifact}')
    lines += [f'  - id: {id_}', f'    name: {name}', '    artifact: ' + str(artifact)]
    if id_ == 'feishu-history':
        import json
        lines += ['    config: ' + json.dumps({'commandPrefix':['/usr/bin/python3',str(ROOT / 'feishu/collector.py')],'timeoutSeconds':25})]
    if inject:
        lines += ['    inject: [llmRuntime, feishu.capture, feishu.browse]' if id_ == 'garden-demo' else '    inject: [llmRuntime]']
bootstrap.write_text('\n'.join(lines)+'\n')
# 同一插件可随主宿主装配；默认独立宿主避免中断已运行的人物服务。
if env.get('WECHAT_HISTORY_EMBEDDED') == '1':
    import json
    from logbook.history_runtime import plugin_config, plugin_row
    row = plugin_row(plugin_config())
    lines += ['  - id: ' + row['id'], '    name: ' + row['name'], '    artifact: ' + row['artifact'], '    config: ' + json.dumps(row['config'], ensure_ascii=False)]
    bootstrap.write_text('\n'.join(lines)+'\n')
jar = BASE / 'app-boot/target/dsh-java.jar'
java = shutil.which('java') or '/opt/homebrew/opt/openjdk@17/bin/java'
os.chdir(ROOT)
# exec 让 Ctrl+C 直接触发底座生命周期关闭，避免遗留子进程。
os.execvpe(java,[java,'-Dfile.encoding=UTF-8','-jar',str(jar),'--dsh.bootstrap='+str(bootstrap),'--spring.main.web-application-type=none'],env)
