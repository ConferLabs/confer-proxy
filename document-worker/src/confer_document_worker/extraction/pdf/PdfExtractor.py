import math
import multiprocessing
import os
import re
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import pymupdf

from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.domain.OutlineEntry import OutlineEntry
from confer_document_worker.extraction.pdf.PageDraft import PageDraft
from confer_document_worker.extraction.pdf.PdfLimits import MAX_PAGES, MAX_PDF_BYTES
from confer_document_worker.extraction.pdf.PdfOcrProcessor import PdfOcrProcessor
from confer_document_worker.extraction.pdf.PdfPageRangeScanner import PdfPageRangeScanner
from confer_document_worker.extraction.pdf.PdfPageScanner import PdfPageScanner


class PdfExtractor:
  PARALLEL_SCAN_MIN_PAGES = 32

  def __init__(
    self,
    page_scanner: PdfPageScanner,
    page_range_scanner: PdfPageRangeScanner,
    ocr_processor: PdfOcrProcessor,
    scan_workers: int | None = None,
    max_scan_workers: int = 8,
  ):
    self.page_scanner = page_scanner
    self.page_range_scanner = page_range_scanner
    self.ocr_processor = ocr_processor
    self.scan_workers = scan_workers
    self.max_scan_workers = max_scan_workers

  def extract(self, source: DocumentSource) -> DocumentContent:
    if source.length == 0:
      raise ValueError("PDF is empty")
    if source.length > MAX_PDF_BYTES:
      raise ValueError("PDF exceeds maximum size")

    try:
      document = pymupdf.open(filename=str(source.path), filetype="pdf")
    except pymupdf.FileDataError as error:
      raise ValueError("PDF is invalid") from error

    with document:
      if document.needs_pass:
        raise ValueError("password-protected PDFs are not supported")
      if document.page_count > MAX_PAGES:
        raise ValueError("PDF exceeds maximum page count")
      page_count = document.page_count
      metadata = document.metadata or {}
      title = self._clean_title(metadata.get("title"))
      outline = tuple(
        OutlineEntry(level, self._clean_title(entry_title) or "Untitled", page_number - 1)
        for level, entry_title, page_number, *_ in document.get_toc(simple=False)
        if 1 <= page_number <= page_count
      )

    drafts = self._scan_pages(source.path, page_count)
    pages = self.ocr_processor.process(source.path, drafts)
    return DocumentContent(
      source_sha256=source.sha256,
      containers=pages,
      title=title,
      outline=outline,
    )

  def _scan_pages(self, source: Path, page_count: int) -> tuple[PageDraft, ...]:
    workers = self._scan_worker_count(page_count)
    if workers == 1:
      with pymupdf.open(filename=str(source), filetype="pdf") as document:
        return tuple(self.page_scanner.scan(page) for page in document)

    chunk_size = math.ceil(page_count / workers)
    ranges = [
      (str(source), start, min(page_count, start + chunk_size))
      for start in range(0, page_count, chunk_size)
    ]
    context = multiprocessing.get_context("fork")
    try:
      with ProcessPoolExecutor(max_workers=workers, mp_context=context) as executor:
        batches = list(executor.map(self.page_range_scanner, ranges))
    except PermissionError:
      # Restricted development sandboxes may block the semaphore limit probe
      # used by ProcessPoolExecutor. Production permits this bounded pool.
      with pymupdf.open(filename=str(source), filetype="pdf") as document:
        return tuple(self.page_scanner.scan(page) for page in document)
    return tuple(sorted(
      (page for batch in batches for page in batch),
      key=lambda page: page.number,
    ))

  def _scan_worker_count(self, page_count: int) -> int:
    if self.scan_workers is not None:
      return max(1, min(self.max_scan_workers, self.scan_workers, page_count))
    if page_count < self.PARALLEL_SCAN_MIN_PAGES:
      return 1
    return max(1, min(self.max_scan_workers, os.cpu_count() or 1, page_count))

  def _clean_title(self, value: str | None) -> str | None:
    if value is None:
      return None
    cleaned = re.sub(r"\s+", " ", value).strip()
    return cleaned[:500] or None
