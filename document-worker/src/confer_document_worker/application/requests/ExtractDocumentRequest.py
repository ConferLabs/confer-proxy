from dataclasses import dataclass

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.WorkerRequest import WorkerRequest
from confer_document_worker.domain.DocumentSource import DocumentSource


@dataclass(frozen=True)
class ExtractDocumentRequest(WorkerRequest):
  source: DocumentSource
  filename: str
  media_type: str

  def __post_init__(self) -> None:
    if not self.filename:
      raise InvalidWorkerRequestError("filename must be a non-empty string")
    if not self.media_type:
      raise InvalidWorkerRequestError("content_type must be a non-empty string")
