from concurrent.futures import Future
from dataclasses import dataclass

from confer_document_worker.domain.TextBlock import TextBlock


@dataclass(frozen=True)
class PendingOcr:
  page_number: int
  page_width: float
  page_height: float
  future: Future[tuple[TextBlock, ...]]
