from dataclasses import dataclass

from confer_document_worker.domain.PageRoute import PageRoute


@dataclass(frozen=True)
class PageAnalysis:
  route: PageRoute
  native_characters: int
  image_coverage: float
  replacement_ratio: float
