import pymupdf

from confer_document_worker.domain.PageRoute import PageRoute
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.extraction.pdf.PageAnalysis import PageAnalysis


class PageClassifier:
  def __init__(
    self,
    min_native_characters: int = 20,
    sparse_native_characters: int = 300,
    min_image_coverage: float = 0.05,
    full_page_image_coverage: float = 0.45,
    max_replacement_ratio: float = 0.02,
  ):
    self.min_native_characters = min_native_characters
    self.sparse_native_characters = sparse_native_characters
    self.min_image_coverage = min_image_coverage
    self.full_page_image_coverage = full_page_image_coverage
    self.max_replacement_ratio = max_replacement_ratio

  def classify(self, page: pymupdf.Page, blocks: tuple[TextBlock, ...]) -> PageAnalysis:
    text = "".join(block.text for block in blocks)
    non_whitespace = sum(not character.isspace() for character in text)
    replacements = text.count("\ufffd")
    replacement_ratio = replacements / max(1, len(text))
    if replacement_ratio <= self.max_replacement_ratio and non_whitespace >= self.sparse_native_characters:
      return PageAnalysis(PageRoute.NATIVE, non_whitespace, 0.0, replacement_ratio)

    image_coverage = self._image_coverage(page)
    if non_whitespace == 0:
      route = PageRoute.OCR if image_coverage > self.min_image_coverage else PageRoute.BLANK
    else:
      requires_ocr = (
        replacement_ratio > self.max_replacement_ratio
        or (
          image_coverage >= self.full_page_image_coverage
          and non_whitespace < self.sparse_native_characters
        )
        or (
          non_whitespace < self.min_native_characters
          and image_coverage > self.min_image_coverage
        )
      )
      route = PageRoute.OCR if requires_ocr else PageRoute.NATIVE

    return PageAnalysis(route, non_whitespace, image_coverage, replacement_ratio)

  def _image_coverage(self, page: pymupdf.Page) -> float:
    page_area = max(1.0, page.rect.width * page.rect.height)
    covered = 0.0
    for image in page.get_image_info(hashes=False, xrefs=False):
      rect = pymupdf.Rect(image["bbox"]) & page.rect
      if rect.is_empty:
        continue
      covered += rect.width * rect.height
    return min(1.0, covered / page_area)
