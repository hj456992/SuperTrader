#!/usr/bin/env python3
"""Launch only the expert-lab plugin with the existing DSH runtime."""
import argparse
import json
import os
import re
from pathlib import Path
import shutil
import socket
import sys

ROOT = Path(__file__).resolve().parent
BUNDLED = Path('/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    base = Path(os.environ.get('DSH_JAVA_HOME', '/Users/hou/Documents/Codex/projects/dsh-java'))
    port = int(os.environ.get('EXPERT_LAB_PORT', '48760'))
    data = Path(os.environ.get('EXPERT_LAB_DATA', str(ROOT / '.local/data'))).resolve()
    java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk@17/bin/java')
    python = os.environ.get('EXPERT_PYTHON', str(BUNDLED) if BUNDLED.exists() else sys.executable)
    db_url = os.environ.get('EXPERT_DB_URL', '')
    if db_url:
        if not db_url.startswith('jdbc:postgresql:'):
            raise ValueError('EXPERT_DB_URL 必须为 PostgreSQL JDBC 地址')
        schema = os.environ.get('EXPERT_DB_SCHEMA', 'expert_production')
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', schema):
            raise ValueError('EXPERT_DB_SCHEMA 格式无效')
        shared = Path(os.environ.get('EXPERT_SHARED_DIR', str(data / 'shared'))).resolve()
        shared.mkdir(parents=True, exist_ok=True)
        if not os.access(shared, os.R_OK | os.W_OK):
            raise ValueError('专家生产共享目录不可读写')
        os.environ['EXPERT_SHARED_DIR'] = str(shared)
    rows = []
    for ident, module, inject in [
        ('model-registry', 'model-registry', []), ('model-deepseek', 'model-deepseek', ['llmRuntime']),
        ('sessions', 'session', ['sessionProjections']), ('session-projections', 'session-projection', []),
        ('agents', 'agent', []), ('prompt-context', 'context', []), ('tools', 'tools', ['systemPrompt']),
        ('agent-loop', 'agent-loop', ['agents','sessions','sessionProjections','systemPrompt','tools','toolScheduler','llmRuntime'])]:
        row = dict(id=ident, name='dsh-java-'+module, artifact=str(base / f'plugins/{module}/target/{module}.jar'))
        if inject:
            row['inject'] = inject
        rows.append(row)
    rows.append(dict(id='expert-lab', name='ailiao-expert-lab', artifact=str(ROOT / 'target/expert-lab.jar'),
                     inject=['llmRuntime','agents','systemPrompt','tools'],
                     config=dict(port=port, dataDir=str(data), python=python, extractor=str(ROOT / 'extract_pdf.py'),
                                 provider=os.environ.get('EXPERT_PROVIDER','deepseek'), model=os.environ.get('EXPERT_MODEL','deepseek-v4-flash'))))
    required = [base / 'app-boot/target/dsh-java.jar', Path(java), Path(python), ROOT / 'extract_pdf.py'] + [Path(r['artifact']) for r in rows]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        raise ValueError('缺少运行文件：' + ', '.join(missing))
    if not os.environ.get('DEEPSEEK_API_KEY'):
        raise ValueError('请在启动环境中设置 DEEPSEEK_API_KEY；密钥不会写入文件或页面')
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', port))
    if args.check:
        print('本地依赖、端口及配置格式预检通过；数据库、Redis和真实模型连接仍需运行验收。')
        if not db_url:
            print('未配置 EXPERT_DB_URL：旧资料库可用，管理员生产接口将返回未配置。')
        return
    runtime = ROOT / '.local'
    runtime.mkdir(mode=0o700, exist_ok=True)
    runtime.chmod(0o700)
    bootstrap = runtime / 'bootstrap.yml'
    fd = os.open(bootstrap, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write('plugins:\n')
        for row in rows:
            out.write('  - id: ' + json.dumps(row['id']) + '\n')
            for key, value in row.items():
                if key != 'id':
                    out.write('    ' + key + ': ' + json.dumps(value, ensure_ascii=False) + '\n')
    os.chdir(ROOT)
    os.execv(java, [java, '-Dfile.encoding=UTF-8', '-jar', str(base / 'app-boot/target/dsh-java.jar'), '--dsh.bootstrap='+str(bootstrap), '--spring.main.web-application-type=none'])


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as error:
        print('启动失败：'+str(error), file=sys.stderr)
        sys.exit(1)
