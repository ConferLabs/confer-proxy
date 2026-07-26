from dataclasses import dataclass

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse


@dataclass(frozen=True)
class EmptyResponse(WorkerResponse):
  pass
