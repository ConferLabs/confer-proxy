import re
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pymupdf

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.PageRoute import PageRoute
from confer_document_worker.domain.RasterizationPolicy import RasterizationPolicy
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion
from confer_document_worker.domain.VisualRegionType import VisualRegionType
from confer_document_worker.extraction.pdf.OcrBackend import OcrBackend
from confer_document_worker.extraction.pdf.OcrRequiredError import OcrRequiredError
from confer_document_worker.extraction.pdf.PageDraft import PageDraft
from confer_document_worker.extraction.pdf.PendingOcr import PendingOcr
from confer_document_worker.extraction.pdf.PdfLimits import OCR_DPI
from confer_document_worker.extraction.pdf.RasterImage import RasterImage


class PdfOcrProcessor:
  NUMERIC_TOKEN = re.compile(
    r"(?<!\w)[<>]?\d+(?:[.,]\d+)?(?:-\d+(?:[.,]\d+)?)?",
  )
  LABEL_TOKEN = re.compile(r"[A-Za-z]{2,}")
  TABLE_REGION_CONFIDENCE = 0.6
  LOW_CONFIDENCE_THRESHOLD = 0.85
  MIN_NUMERIC_LINES = 4
  MIN_LABELED_LINES = 8
  MAX_LABELED_LINE_CHARACTERS = 100
  MIN_EMBEDDED_IMAGE_COVERAGE = 0.4

  def __init__(
    self,
    backend: OcrBackend | None = None,
    max_concurrency: int = 8,
    rasterization: RasterizationPolicy | None = None,
  ):
    self.backend = backend
    self.max_concurrency = max_concurrency
    self.rasterization = rasterization or RasterizationPolicy()

  def process(
    self,
    source: Path,
    drafts: tuple[PageDraft, ...],
  ) -> tuple[DocumentContainer, ...]:
    pages: list[DocumentContainer | None] = [None] * len(drafts)
    ocr_drafts: list[PageDraft] = []
    for draft in drafts:
      if draft.route == PageRoute.NATIVE:
        pages[draft.number] = DocumentContainer(
          draft.number,
          draft.width,
          draft.height,
          draft.blocks,
          draft.regions,
        )
      elif draft.route == PageRoute.BLANK:
        pages[draft.number] = DocumentContainer(draft.number, draft.width, draft.height)
      else:
        ocr_drafts.append(draft)

    if not ocr_drafts:
      return self._completed_pages(pages)
    if self.backend is None:
      raise OcrRequiredError(f"page {ocr_drafts[0].number + 1} requires OCR")

    concurrency = max(
      1,
      min(self.max_concurrency, getattr(self.backend, "max_concurrency", 1)),
    )
    executor = ThreadPoolExecutor(
      max_workers=concurrency,
      thread_name_prefix="document-ocr",
    )
    pending: deque[PendingOcr] = deque()
    try:
      with pymupdf.open(filename=str(source), filetype="pdf") as document:
        for draft in ocr_drafts:
          pending.append(PendingOcr(
            draft.number,
            draft.width,
            draft.height,
            executor.submit(
              self.backend.extract,
              self._rasterize(document[draft.number]),
            ),
          ))
          if len(pending) >= concurrency:
            self._resolve(pages, pending.popleft())
      for item in pending:
        self._resolve(pages, item)
    finally:
      executor.shutdown(wait=True, cancel_futures=True)
    return self._completed_pages(pages)

  def _resolve(
    self,
    pages: list[DocumentContainer | None],
    pending: PendingOcr,
  ) -> None:
    blocks = pending.future.result()
    page_bounds = Bounds(0, 0, pending.page_width, pending.page_height)
    pages[pending.page_number] = DocumentContainer(
      pending.page_number,
      pending.page_width,
      pending.page_height,
      blocks,
      self._regions(pending.page_number, page_bounds, blocks),
    )

  def _regions(
    self,
    page_number: int,
    page_bounds: Bounds,
    blocks: tuple[TextBlock, ...],
  ) -> tuple[VisualRegion, ...]:
    confidence = sum(block.confidence for block in blocks) / max(1, len(blocks))
    regions = [
      VisualRegion(
        f"p{page_number + 1}-scanned-page",
        VisualRegionType.SCANNED_PAGE,
        page_bounds,
        confidence,
      ),
    ]
    if self._looks_tabular(blocks):
      regions.append(VisualRegion(
        f"p{page_number + 1}-ocr-table",
        VisualRegionType.TABLE,
        page_bounds,
        self.TABLE_REGION_CONFIDENCE,
      ))
    if confidence < self.LOW_CONFIDENCE_THRESHOLD:
      regions.append(VisualRegion(
        f"p{page_number + 1}-ocr-low-confidence",
        VisualRegionType.LOW_CONFIDENCE,
        page_bounds,
        confidence,
      ))
    return tuple(regions)

  def _looks_tabular(self, blocks: tuple[TextBlock, ...]) -> bool:
    numeric_lines = 0
    short_labeled_lines = 0
    for block in blocks:
      for line in block.text.splitlines():
        cleaned = line.strip()
        if not cleaned:
          continue
        numeric_tokens = self.NUMERIC_TOKEN.findall(cleaned)
        if len(numeric_tokens) >= 2:
          numeric_lines += 1
        if (
          numeric_tokens
          and len(cleaned) <= self.MAX_LABELED_LINE_CHARACTERS
          and self.LABEL_TOKEN.search(cleaned)
        ):
          short_labeled_lines += 1
    return (
      numeric_lines >= self.MIN_NUMERIC_LINES
      or short_labeled_lines >= self.MIN_LABELED_LINES
    )

  def _rasterize(self, page: pymupdf.Page) -> RasterImage:
    image_info = page.get_image_info(hashes=False, xrefs=True)
    if image_info:
      dominant = max(
        image_info,
        key=lambda image: max(0.0, image["bbox"][2] - image["bbox"][0])
          * max(0.0, image["bbox"][3] - image["bbox"][1]),
      )
      rect = pymupdf.Rect(dominant["bbox"]) & page.rect
      coverage = rect.width * rect.height / max(1.0, page.rect.width * page.rect.height)
      xref = int(dominant.get("xref", 0))
      image_width = int(dominant.get("width", 0))
      image_height = int(dominant.get("height", 0))
      if (
        xref
        and coverage >= self.MIN_EMBEDDED_IMAGE_COVERAGE
        and self.rasterization.permits(image_width, image_height)
      ):
        pixmap = pymupdf.Pixmap(page.parent, xref)
        if pixmap.colorspace is None or pixmap.colorspace.n != 3:
          pixmap = pymupdf.Pixmap(pymupdf.csRGB, pixmap)
        if pixmap.alpha:
          pixmap = pymupdf.Pixmap(pixmap, 0)
        self.rasterization.validate(pixmap.width, pixmap.height)
        return RasterImage(
          width=pixmap.width,
          height=pixmap.height,
          samples=bytes(pixmap.samples),
          page_width=rect.width,
          page_height=rect.height,
          page_x0=rect.x0,
          page_y0=rect.y0,
        )

    scale = self.rasterization.bounded_scale(
      page.rect.width,
      page.rect.height,
      OCR_DPI,
    )
    pixmap = page.get_pixmap(
      matrix=pymupdf.Matrix(scale, scale),
      colorspace=pymupdf.csRGB,
      alpha=False,
    )
    self.rasterization.validate(pixmap.width, pixmap.height)
    return RasterImage(
      width=pixmap.width,
      height=pixmap.height,
      samples=bytes(pixmap.samples),
      page_width=page.rect.width,
      page_height=page.rect.height,
    )

  def _completed_pages(
    self,
    pages: list[DocumentContainer | None],
  ) -> tuple[DocumentContainer, ...]:
    if any(page is None for page in pages):
      raise RuntimeError("PDF page processing is incomplete")
    return tuple(page for page in pages if page is not None)
