from dataclasses import dataclass

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse
from confer_document_worker.domain.SearchHit import SearchHit


@dataclass(frozen=True)
class DocumentSearchResult(WorkerResponse):
  hits: tuple[SearchHit, ...]
  has_more: bool
