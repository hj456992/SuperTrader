"""Bounded local text extraction. No network, shell, macros or archive extraction."""
import json, posixpath, re, sys, zipfile
from pathlib import Path
from html.parser import HTMLParser
from xml.etree import ElementTree as ET
from urllib.parse import urlsplit, unquote

MAX_CHARACTERS=1_500_000
MAX_EXPANDED=50*1024*1024

def xml(raw):
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise ValueError('资料包含不支持的XML实体，请转换为TXT。')
    return ET.fromstring(raw)

def tag(node):return node.tag.rsplit('}',1)[-1]

def read_zip(path):
    z=zipfile.ZipFile(path)
    info=z.infolist()
    if len(info)>5000 or sum(i.file_size for i in info)>MAX_EXPANDED:
        z.close();raise ValueError('文档展开后过大，请拆分后上传。')
    if any(i.flag_bits&1 for i in info):
        z.close();raise ValueError('暂不支持加密文件，请上传可读取的版本。')
    return z

class Text(HTMLParser):
    def __init__(self):super().__init__(convert_charrefs=True);self.parts=[];self.hidden=0
    def handle_starttag(self,t,a):
        if t in ('script','style'):self.hidden+=1
        if t in ('p','div','br','h1','h2','h3','li'):self.parts.append('\n')
    def handle_endtag(self,t):
        if t in ('script','style'):self.hidden=max(0,self.hidden-1)
        if t in ('p','div','h1','h2','h3','li'):self.parts.append('\n')
    def handle_data(self,d):
        if not self.hidden:self.parts.append(d)

def extract(path):
    path=Path(path);suffix=path.suffix.lower();pages=[]
    if path.stat().st_size>10*1024*1024:raise ValueError('文件不得超过10MB。')
    if suffix in ('.txt','.md'):
        raw=path.read_bytes()
        if b'\x00' in raw:raise ValueError('文字编码不受支持，请转换为UTF-8。')
        try:text=raw.decode('utf-8-sig')
        except UnicodeDecodeError:text=raw.decode('gb18030')
        pages=[dict(location='正文',text=text)]
    elif suffix=='.pdf':
        from pypdf import PdfReader
        reader=PdfReader(path)
        if reader.is_encrypted:raise ValueError('请上传未加密的PDF文件。')
        if len(reader.pages)>1000:raise ValueError('PDF超过1000页，请拆分后上传。')
        count=0
        for i,page in enumerate(reader.pages):
            text=page.extract_text() or '';count+=len(text)
            if count>MAX_CHARACTERS:raise ValueError('文字超过150万字，请拆分资料。')
            pages.append(dict(location=f'第{i+1}页',text=text))
    elif suffix=='.docx':
        with read_zip(path) as z:
            root=xml(z.read('word/document.xml'))
            paragraphs=[''.join(n.text or '' for n in p.iter() if tag(n)=='t') for p in root.iter() if tag(p)=='p']
            pages=[dict(location='文档正文',text='\n'.join(paragraphs))]
    elif suffix=='.epub':
        with read_zip(path) as z:
            container=xml(z.read('META-INF/container.xml'))
            opf=next(n.attrib['full-path'] for n in container.iter() if tag(n)=='rootfile')
            root=xml(z.read(opf));base=posixpath.dirname(opf)
            manifest={n.attrib['id']:n.attrib['href'] for n in root.iter() if tag(n)=='item'}
            for i,n in enumerate(n for n in root.iter() if tag(n)=='itemref'):
                href=manifest.get(n.attrib.get('idref'))
                if not href:continue
                url=urlsplit(href)
                if url.scheme or url.netloc:raise ValueError('EPUB章节路径无效。')
                decoded=unquote(url.path)
                if decoded.startswith('/') or '\\' in decoded:raise ValueError('EPUB章节路径无效。')
                target=posixpath.normpath(posixpath.join(base,decoded))
                if target.startswith('../') or target.startswith('/'):raise ValueError('EPUB章节路径无效。')
                raw=z.read(target)
                if len(raw)>MAX_EXPANDED:raise ValueError('章节内容过大。')
                parser=Text();parser.feed(raw.decode('utf-8-sig'))
                pages.append(dict(location=f'第{i+1}章',text=''.join(parser.parts)))
    else:raise ValueError('不支持的资料格式。')
    result=[];total=0
    for page in pages:
        text=re.sub(r'\n[ \t]*\n(?:[ \t]*\n)+','\n\n',page['text']).strip();total+=len(text)
        if total>MAX_CHARACTERS:raise ValueError('文字超过150万字，请拆分资料。')
        if text:result.append(dict(location=page['location'],text=text))
    if not result:raise ValueError('没有提取到文字；扫描PDF请先转换为可选中文字的版本。')
    return result

if __name__=='__main__':
    try:result=dict(pages=extract(sys.argv[1]))
    except ValueError as e:result=dict(error=str(e) if len(str(e))<150 else '无法解析文件，请检查格式或转换为TXT。')
    except Exception:result=dict(error='无法解析文件，请检查格式或转换为TXT。')
    Path(sys.argv[2]).write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
