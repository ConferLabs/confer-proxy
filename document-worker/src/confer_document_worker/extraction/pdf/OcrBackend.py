from typing import Protocol

from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.extraction.pdf.RasterImage import RasterImage


class OcrBackend(Protocol):
  max_concurrency: int

  def extract(self, image: RasterImage) -> tuple[TextBlock, ...]: ...
