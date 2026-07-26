import pymupdf

from confer_document_worker.extraction.pdf.PageDraft import PageDraft
from confer_document_worker.extraction.pdf.PdfPageScanner import PdfPageScanner


class PdfPageRangeScanner:
  def __init__(self, page_scanner: PdfPageScanner):
    self.page_scanner = page_scanner

  def __call__(self, page_range: tuple[str, int, int]) -> tuple[PageDraft, ...]:
    source, start, end = page_range
    with pymupdf.open(filename=source, filetype="pdf") as document:
      return tuple(
        self.page_scanner.scan(document[page_number])
        for page_number in range(start, end)
      )
