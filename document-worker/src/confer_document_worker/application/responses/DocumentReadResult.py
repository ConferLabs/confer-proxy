from dataclasses import dataclass

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse
from confer_document_worker.domain.TextWindow import TextWindow


@dataclass(frozen=True)
class DocumentReadResult(WorkerResponse):
  window: TextWindow
