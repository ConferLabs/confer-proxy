import re

import pymupdf

from confer_document_worker.domain.PageRoute import PageRoute
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.TextSource import TextSource
from confer_document_worker.domain.VisualRegion import VisualRegion
from confer_document_worker.domain.VisualRegionType import VisualRegionType
from confer_document_worker.extraction.pdf.PageClassifier import PageClassifier
from confer_document_worker.extraction.pdf.PageDraft import PageDraft
from confer_document_worker.extraction.pdf.TableDetector import TableDetector


class PdfPageScanner:
  COLUMN_GAP = re.compile(r"(?<=\S)\s{4,}(?=\S)")
  MIN_IMAGE_COVERAGE = 0.005
  TABLE_REGION_CONFIDENCE = 0.75
  MIN_TABLE_SCAN_BLOCKS = 12
  MIN_TABLE_GAP_SCORE = 100
  MIN_COMPACT_LINES = 3
  MAX_COMPACT_LINES = 6
  MAX_COMPACT_LINE_CHARACTERS = 40
  MIN_COMPACT_COLUMN_BLOCKS = 3

  def __init__(
    self,
    classifier: PageClassifier,
    table_detector: TableDetector,
  ):
    self.classifier = classifier
    self.table_detector = table_detector

  def scan(self, page: pymupdf.Page) -> PageDraft:
    native_blocks = self._native_blocks(page)
    analysis = self.classifier.classify(page, native_blocks)
    width = round(page.rect.width, 2)
    height = round(page.rect.height, 2)
    if analysis.route == PageRoute.NATIVE:
      return PageDraft(
        page.number,
        width,
        height,
        analysis.route,
        native_blocks,
        self._native_regions(page, native_blocks),
      )
    return PageDraft(page.number, width, height, analysis.route, (), ())

  def _native_blocks(self, page: pymupdf.Page) -> tuple[TextBlock, ...]:
    blocks: list[TextBlock] = []
    for block in page.get_text("blocks", sort=True):
      if block[6] != 0 or not block[4].strip():
        continue
      bounds = self.table_detector.bounds(block[:4], page.rect)
      if bounds is None:
        continue
      blocks.append(TextBlock(block[4], bounds, TextSource.NATIVE, 1.0))
    return tuple(blocks)

  def _native_regions(
    self,
    page: pymupdf.Page,
    blocks: tuple[TextBlock, ...],
  ) -> tuple[VisualRegion, ...]:
    regions: list[VisualRegion] = []
    page_area = max(1.0, page.rect.width * page.rect.height)
    images = page.get_image_info(hashes=False, xrefs=False) if page.get_images(full=True) else ()
    for image_index, image in enumerate(images):
      bounds = self.table_detector.bounds(image["bbox"], page.rect)
      if bounds is None:
        continue
      area = (bounds.x1 - bounds.x0) * (bounds.y1 - bounds.y0)
      if area / page_area < self.MIN_IMAGE_COVERAGE:
        continue
      regions.append(VisualRegion(
        f"p{page.number + 1}-image-{image_index + 1}",
        VisualRegionType.IMAGE,
        bounds,
      ))

    if self._may_contain_table(blocks):
      for table_index, bounds in enumerate(self.table_detector.detect(page)):
        regions.append(VisualRegion(
          f"p{page.number + 1}-table-{table_index + 1}",
          VisualRegionType.TABLE,
          bounds,
          self.TABLE_REGION_CONFIDENCE,
        ))
    return tuple(regions)

  def _may_contain_table(self, blocks: tuple[TextBlock, ...]) -> bool:
    if len(blocks) >= self.MIN_TABLE_SCAN_BLOCKS:
      return True
    gap_score = 0
    compact_column_blocks = 0
    for block in blocks:
      lines = [line.strip() for line in block.text.splitlines() if line.strip()]
      gap_score += sum(
        len(match.group(0))
        for line in lines
        for match in self.COLUMN_GAP.finditer(line)
      )
      if (
        self.MIN_COMPACT_LINES <= len(lines) <= self.MAX_COMPACT_LINES
        and sum(len(line) for line in lines) / len(lines)
        < self.MAX_COMPACT_LINE_CHARACTERS
      ):
        compact_column_blocks += 1
      if gap_score >= self.MIN_TABLE_GAP_SCORE:
        return True
    return compact_column_blocks >= self.MIN_COMPACT_COLUMN_BLOCKS
