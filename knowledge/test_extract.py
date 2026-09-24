import unittest, tempfile, zipfile
from pathlib import Path
from extract import extract

class ExtractTest(unittest.TestCase):
    def setUp(self): self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)
    def tearDown(self): self.temp.cleanup()
    def test_text_preserves_chinese_and_source(self):
        p=self.root/'book.txt';p.write_text('认识新朋友：从共同兴趣开始。',encoding='utf-8')
        pages=extract(p)
        self.assertIn('共同兴趣',pages[0]['text']);self.assertTrue(pages[0]['location'])
    def test_docx_reads_paragraphs_not_archive_metadata(self):
        p=self.root/'book.docx'
        with zipfile.ZipFile(p,'w') as z:z.writestr('word/document.xml','<w:document xmlns:w="urn:word"><w:p><w:r><w:t>认真倾听</w:t></w:r></w:p><w:p><w:r><w:t>表达自己的边界</w:t></w:r></w:p></w:document>')
        self.assertIn('认真倾听\n表达自己的边界',extract(p)[0]['text'])
    def test_epub_uses_spine_order_and_ignores_scripts(self):
        p=self.root/'book.epub'
        with zipfile.ZipFile(p,'w') as z:
            z.writestr('META-INF/container.xml','<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
            z.writestr('OEBPS/content.opf','<package><manifest><item id="b" href="b.xhtml"/><item id="a" href="a.xhtml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>')
            z.writestr('OEBPS/a.xhtml','<html><body><h1>先倾听</h1><script>secret()</script></body></html>')
            z.writestr('OEBPS/b.xhtml','<html><body><p>再表达</p></body></html>')
        pages=extract(p);self.assertIn('先倾听',pages[0]['text']);self.assertNotIn('secret',pages[0]['text']);self.assertIn('再表达',pages[1]['text'])
    def test_empty_scanned_pdf_has_actionable_error(self):
        from pypdf import PdfWriter
        p=self.root/'scan.pdf';w=PdfWriter();w.add_blank_page(100,100);w.write(p)
        with self.assertRaisesRegex(ValueError,'扫描'):extract(p)
    def test_epub_decodes_chapter_url_and_rejects_remote_paths(self):
        for href,valid in [('Chapter%201.xhtml#start',True),('https://example.org/book.xhtml',False),('%2fetc/passwd',False),('../../../book.xhtml',False)]:
            p=self.root/'encoded.epub'
            with zipfile.ZipFile(p,'w') as z:
                z.writestr('META-INF/container.xml','<container><rootfile full-path="OEBPS/content.opf"/></container>')
                z.writestr('OEBPS/content.opf',f'<package><item id="a" href="{href}"/><itemref idref="a"/></package>')
                z.writestr('OEBPS/Chapter 1.xhtml','<p>共同兴趣</p>')
            if valid:self.assertEqual('共同兴趣',extract(p)[0]['text'])
            else:
                with self.assertRaisesRegex(ValueError,'路径'):extract(p)
    def test_rejects_xml_entities(self):
        p=self.root/'bad.docx'
        with zipfile.ZipFile(p,'w') as z:z.writestr('word/document.xml','<!DOCTYPE x [<!ENTITY a "foo">]><x>&a;</x>')
        with self.assertRaises(ValueError):extract(p)

if __name__=='__main__':unittest.main()
