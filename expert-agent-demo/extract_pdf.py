"""Bounded PDF text extraction for the DSH plugin; no OCR or network access."""
import json
import os
import sys
import tempfile
from pathlib import Path
from pypdf import PdfReader, PdfWriter


def split(path, folder):
    source = Path(path)
    if source.stat().st_size > 20 * 1024 * 1024:
        raise ValueError('PDF 不得超过 20MB')
    with source.open('rb') as stream:
        if not stream.read(1024).lstrip().startswith(b'%PDF-'):
            raise ValueError('文件不是有效 PDF')
    reader = PdfReader(source)
    if reader.is_encrypted and not reader.decrypt(''):
        raise ValueError('PDF 需要打开密码；未改写原文件')
    count = len(reader.pages)
    if count < 1 or count > 600:
        raise ValueError('PDF 页数无效或超过 MinerU 600 页上限')
    destination = Path(folder)
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    manifest = []
    for start in range(0, count, 200):
        end = min(start + 200, count)
        filename = f'part-{len(manifest) + 1:03d}.pdf'
        writer = PdfWriter()
        for page in reader.pages[start:end]:
            writer.add_page(page)
        descriptor, temporary = tempfile.mkstemp(prefix='.part-', suffix='.pdf', dir=destination)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                writer.write(output)
            part = PdfReader(temporary)
            if part.is_encrypted or len(part.pages) != end - start:
                raise ValueError('PDF 临时分片页数校验失败')
            os.replace(temporary, destination / filename)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        manifest.append({'index': len(manifest), 'startPage': start + 1,
                         'endPage': end, 'pageCount': end - start, 'filename': filename})
    return {'parts': manifest}


def extract(path):
    source = Path(path)
    if source.stat().st_size > 20 * 1024 * 1024:
        raise ValueError('PDF 不得超过 20MB')
    if not source.read_bytes()[:1024].lstrip().startswith(b'%PDF-'):
        raise ValueError('文件不是有效 PDF')
    reader = PdfReader(source)
    if reader.is_encrypted:
        raise ValueError('暂不支持加密 PDF，请先解除密码')
    if len(reader.pages) > 500:
        raise ValueError('Demo 最多支持 500 页 PDF')
    pages, total = [], 0
    for number, page in enumerate(reader.pages, 1):
        text = page.extract_text() or ''
        total += len(text)
        if total > 250000:
            raise ValueError('Demo 最多支持 25 万字，请拆分文档')
        pages.append({'page': number, 'text': text})
    if not any(p['text'].strip() for p in pages):
        raise ValueError('未提取到文字：扫描版 PDF 需要先 OCR，本 Demo 不含 OCR')
    return {'pages': pages}


if __name__ == '__main__':
    try:
        if len(sys.argv) > 4 and sys.argv[4] == '--split':
            result = split(sys.argv[1], sys.argv[3])
        elif len(sys.argv) > 3 and sys.argv[3] == '--inspect':
            reader = PdfReader(Path(sys.argv[1]))
            encrypted = reader.is_encrypted
            if encrypted and not reader.decrypt(''):
                raise ValueError('PDF 需要打开密码；未改写原文件')
            result = {'pageCount': len(reader.pages), 'encrypted': encrypted, 'emptyPasswordReadable': True}
        else:
            result = extract(sys.argv[1])
    except ValueError as error:
        result = {'error': str(error)}
    except Exception:
        result = {'error': 'PDF 无法解析，请检查文件是否损坏或转换为可选中文字的 PDF'}
    Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
