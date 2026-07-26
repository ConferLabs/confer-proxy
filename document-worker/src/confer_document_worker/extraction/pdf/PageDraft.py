from dataclasses import dataclass

from confer_document_worker.domain.PageRoute import PageRoute
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion


@dataclass(frozen=True)
class PageDraft:
  number: int
  width: float
  height: float
  route: PageRoute
  blocks: tuple[TextBlock, ...]
  regions: tuple[VisualRegion, ...]
