"""Parse native WeChat export bytes; never execute or extract archive paths."""
from datetime import datetime
import io
from pathlib import PurePosixPath
import re
import stat
import unicodedata
import zipfile

TARGET = '卧底不追高'
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024
MAX_TEXT = 8 * 1024 * 1024
HEADER = re.compile(r'(?m)^·([^\n]+)\n(\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2})\n')
MEDIA = re.compile(r'^\[(图片|视频|文件|语音)\]\s*(.+?)\s*$', re.M)

class ArchiveError(ValueError):
    pass


def transcript(text):
    text = text.lstrip('\ufeff').replace('\r\n', '\n')
    if text.startswith('聊天：'):
        title, _, text = text.partition('\n')
        if title[3:].strip() != TARGET:
            raise ArchiveError('导出文件中的群名与当前日志不一致。')
    matches = list(HEADER.finditer(text))
    if not matches or matches[0].start() != 0 or len(matches) > 20000:
        raise ArchiveError('未识别的微信原生聊天记录格式，原文未入库。')
    result = []
    for index, match in enumerate(matches):
        label = match.group(2)
        try:
            datetime.strptime(label, '%Y年%m月%d日 %H:%M')
        except ValueError:
            raise ArchiveError('导出记录包含无效日期。') from None
        author = match.group(1).strip()
        if not author or len(author) > 256:
            raise ArchiveError('导出记录包含无效成员名。')
        end = matches[index+1].start() if index+1 < len(matches) else len(text)
        body = text[match.end():end].strip('\n')
        # An unrecognized top-level record must not silently become the prior body.
        if re.search(r'(?m)^·[^\n]+\n\d{4}年', body):
            raise ArchiveError('记录分隔或日期无法确认，未部分导入。')
        marks = MEDIA.findall(body)
        kind = {'图片': 'image', '视频': 'video', '文件': 'file', '语音': 'audio'}.get(marks[0][0], 'text') if marks else 'text'
        result.append(dict(author=author, text=body, sentAtLabel=label, timePrecision='minute',
                           sourceMessageId=None, kind=kind, attachmentNames=[m[1] for m in marks]))
    return result


def parse_archive(data):
    if not data or len(data) > MAX_ARCHIVE:
        raise ArchiveError('消息包为空或超过 64 MB。')
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            if not 1 <= len(infos) <= 2000 or sum(i.file_size for i in infos) > MAX_EXPANDED:
                raise ArchiveError('消息包文件过多或展开体积过大。')
            paths, aliases = {}, set()
            for info in infos:
                name = info.filename.rstrip('/')
                parts = name.split('/')
                alias = unicodedata.normalize('NFC', name).casefold()
                filetype = stat.S_IFMT(info.external_attr >> 16)
                if (not name or name.startswith('/') or any(p in ('', '.', '..') for p in parts)
                        or '\\' in name or ':' in name or any(ord(c)<32 for c in name)
                        or alias in aliases or filetype not in (0, stat.S_IFREG, stat.S_IFDIR)
                        or info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED)):
                    raise ArchiveError('消息包含不支持或不安全的文件条目。')
                aliases.add(alias)
                if not info.is_dir():paths[name] = info
            texts = [n for n in paths if PurePosixPath(n).name == '聊天记录.txt']
            if len(texts) != 1 or paths[texts[0]].file_size > MAX_TEXT:
                raise ArchiveError('消息包需包含唯一的聊天记录.txt。')
            source = texts[0]
            records = transcript(archive.read(paths[source]).decode('utf-8-sig'))
            by_name = {}
            for name in paths:
                if name != source:by_name.setdefault(PurePosixPath(name).name, []).append(name)
            needed = set()
            for record in records:
                selected = []
                for name in record.pop('attachmentNames'):
                    relative = str(PurePosixPath(source).parent / name)
                    if relative in paths and relative != source:chosen = relative
                    elif name in paths and name != source:chosen = name
                    elif len(by_name.get(name, [])) == 1:chosen = by_name[name][0]
                    else:continue
                    selected.append(chosen);needed.add(chosen)
                record['attachments'] = selected
            files = {name: archive.read(paths[name]) for name in needed}
            return dict(messages=records, files=files, transcript=source)
    except ArchiveError:
        raise
    except (zipfile.BadZipFile, UnicodeError, OSError, RuntimeError, ValueError, NotImplementedError):
        raise ArchiveError('消息包损坏或编码不支持，未导入。') from None
