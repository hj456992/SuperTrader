"""Verify PDF rejection paths using generated, non-user inputs."""
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
from pypdf import PdfWriter
root=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('extractor',root/'extract_pdf.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
with TemporaryDirectory() as folder:
    folder=Path(folder)
    for kind in ('blank','encrypted','invalid'):
        path=folder/(kind+'.pdf')
        if kind=='invalid':path.write_bytes(b'not a pdf')
        else:
            writer=PdfWriter();writer.add_blank_page(width=200,height=200)
            if kind=='encrypted':writer.encrypt('test-only')
            writer.write(path)
        try:module.extract(path)
        except ValueError as error:print('PASS',kind,str(error))
        else:raise AssertionError(kind+' accepted unexpectedly')
pages=module.extract(root/'evidence/演示资料-职场沟通方法.pdf')['pages']
assert len(pages)==3 and '确认' in pages[0]['text']
print('PASS actual Chinese PDF extracts 3 ordered pages')
