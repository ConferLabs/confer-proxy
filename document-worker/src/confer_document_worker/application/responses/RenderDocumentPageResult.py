from dataclasses import dataclass

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse


@dataclass(frozen=True)
class RenderDocumentPageResult(WorkerResponse):
  png: bytes
  width: int
  height: int
  container_number: int
