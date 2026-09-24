#!/usr/bin/env python3
"""装配已有 dsh-java；--check 无启动/写盘/数据库操作，凭据仅经环境传递。"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
DEFAULT_BASE = '/Users/hou/Documents/Codex/projects/dsh-java'
DB_KEYS = ('GARDEN_DB_URL', 'GARDEN_DB_USER', 'GARDEN_DB_PASSWORD')


class PreflightError(Exception):
    pass


def runtime_environment(source, base):
    env = dict(source)
    if not env.get('DEEPSEEK_API_KEY', '').strip():
        raise PreflightError('请在进程环境中配置 DEEPSEEK_API_KEY。')
    if any(key in env for key in DB_KEYS):
        missing = [key for key in DB_KEYS if not env.get(key, '').strip()]
        if missing:
            raise PreflightError('数据库配置不完整：' + ', '.join(missing))
    else:
        # Compatibility: read existing local settings; never create a database.
        try:
            credentials = {}
            for line in (base / '.local/postgres.env').read_text().splitlines():
                if '=' in line and not line.lstrip().startswith('#'):
                    key, value = line.split('=', 1)
                    credentials[key.strip()] = value.strip().strip('"').strip("'")
            env.update(GARDEN_DB_URL='jdbc:postgresql://127.0.0.1:15440/garden_demo_20260922',
                       GARDEN_DB_USER=credentials['POSTGRES_USER'],
                       GARDEN_DB_PASSWORD=credentials['POSTGRES_PASSWORD'])
            if not all(env[key].strip() for key in DB_KEYS):
                raise ValueError()
        except (OSError, KeyError, ValueError):
            raise PreflightError('无法读取现有数据库配置；请同时提供 GARDEN_DB_URL、GARDEN_DB_USER、GARDEN_DB_PASSWORD。') from None
    if not env['GARDEN_DB_URL'].startswith('jdbc:postgresql://'):
        raise PreflightError('GARDEN_DB_URL 必须为 PostgreSQL JDBC URL。')
    try:
        port = int(env.get('GARDEN_PORT', '48740'))
        if not 1 <= port <= 65535:
            raise ValueError()
    except ValueError:
        raise PreflightError('GARDEN_PORT 必须是 1–65535 的端口号。') from None
    env['GARDEN_PORT'] = str(port)
    env['GARDEN_WEB'] = str(ROOT / 'web/dist')
    return env


def plugin_rows(base):
    rows = [
        dict(id='model-registry', name='dsh-java-model-registry',
             artifact=str(base / 'plugins/model-registry/target/model-registry.jar')),
        dict(id='model-deepseek', name='dsh-java-model-deepseek',
             artifact=str(base / 'plugins/model-deepseek/target/model-deepseek.jar'), inject=['llmRuntime']),
        dict(id='feishu-history', name='feishu-history-plugin',
             artifact=str(ROOT / 'plugins/feishu-history/target/feishu-history-plugin.jar'),
             config=dict(commandPrefix=['/usr/bin/python3', str(ROOT / 'feishu/collector.py')], timeoutSeconds=25)),
        dict(id='garden-demo', name='garden-demo', artifact=str(ROOT / 'target/garden-demo.jar'),
             inject=['llmRuntime', 'feishu.capture', 'feishu.browse', 'agents', 'systemPrompt', 'tools']),
    ]
    # Backend manifest plus verified ModelRegistry.KEY (llmRuntime).
    dependencies = [
        ('sessions', 'session', ['sessionProjections']),
        ('session-projections', 'session-projection', []),
        ('agents', 'agent', []),
        ('prompt-context', 'context', []),
        ('tools', 'tools', ['systemPrompt']),
        ('agent-loop', 'agent-loop', ['agents', 'sessions', 'sessionProjections',
                                    'systemPrompt', 'tools', 'toolScheduler', 'llmRuntime']),
    ]
    for id_, module, inject in dependencies:
        row = dict(id=id_, name='dsh-java-' + module,
                   artifact=str(base / f'plugins/{module}/target/{module}.jar'))
        if inject:
            row['inject'] = inject
        rows.insert(-1, row)
    return rows


def preflight(env, base, rows):
    required = [('dsh-java.jar', base / 'app-boot/target/dsh-java.jar'),
                ('web/dist/index.html', ROOT / 'web/dist/index.html'),
                ('feishu/collector.py', ROOT / 'feishu/collector.py')]
    required += [(row['id'], Path(row['artifact'])) for row in rows]
    if env.get('WECHAT_HISTORY_EMBEDDED') == '1':
        required.append(('wechat-history', ROOT / 'plugins/wechat-history/target/wechat-history-plugin.jar'))
    missing = [label for label, path in required if not path.is_file()]
    if missing:
        raise PreflightError('缺少构建产物或运行文件：' + ', '.join(missing))
    java = env.get('JAVA_BIN') or shutil.which('java') or '/opt/homebrew/opt/openjdk@17/bin/java'
    try:
        version = subprocess.run([java, '-version'], capture_output=True, text=True,
                                 timeout=10, env=env)
        match = re.search(r'version "(\d+)', version.stdout + version.stderr)
        if version.returncode or not match or int(match.group(1)) < 17:
            raise ValueError()
    except (OSError, ValueError, subprocess.TimeoutExpired):
        raise PreflightError('需要可执行的 Java 17 或更新版本；可设置 JAVA_BIN。') from None
    try:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', int(env['GARDEN_PORT'])))
    except OSError:
        raise PreflightError('GARDEN_PORT 不可绑定；请选择空闲端口，不要停止其他实例。') from None
    return java


def write_bootstrap(rows):
    runtime = ROOT / '.runtime'
    runtime.mkdir(mode=0o700, exist_ok=True)
    runtime.chmod(0o700)
    bootstrap = runtime / 'bootstrap.yml'
    # JSON scalars are valid YAML: quotes/newlines in paths cannot change structure.
    lines = ['plugins:']
    for row in rows:
        lines.append('  - id: ' + json.dumps(row['id']))
        lines.extend('    ' + key + ': ' + json.dumps(value, ensure_ascii=False)
                     for key, value in row.items() if key != 'id')
    fd = os.open(bootstrap, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write('\n'.join(lines) + '\n')
    return bootstrap


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='只读本地依赖预检，不连接数据库或模型')
    args = parser.parse_args()
    try:
        base = Path(os.environ.get('DSH_JAVA_HOME', DEFAULT_BASE)).resolve()
        env = runtime_environment(os.environ, base)
        rows = plugin_rows(base)
        java = preflight(env, base, rows)
        if args.check:
            print('本地预检通过；数据库连通性、插件加载及模型调用仍需独立 smoke 验证。')
            if env.get('WECHAT_HISTORY_EMBEDDED') == '1':
                print('嵌入式微信历史配置未读取；画像 smoke 请禁用 WECHAT_HISTORY_EMBEDDED。')
            return 0
        if env.get('WECHAT_HISTORY_EMBEDDED') == '1':
            from logbook.history_runtime import plugin_config, plugin_row
            rows.append(plugin_row(plugin_config()))
        bootstrap = write_bootstrap(rows)
        os.chdir(ROOT)
        # exec preserves Ctrl+C lifecycle handling; no background process manager.
        os.execvpe(java, [java, '-Dfile.encoding=UTF-8', '-jar', str(base / 'app-boot/target/dsh-java.jar'),
                         '--dsh.bootstrap=' + str(bootstrap), '--spring.main.web-application-type=none'], env)
    except PreflightError as error:
        print('启动预检失败：' + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError):
        print('启动失败：无法读取运行配置、写入私有装配文件或执行 Java；未输出原始异常。', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
