from dataclasses import dataclass

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest


@dataclass(frozen=True)
class ReadDocumentRequest(DocumentSessionRequest):
  document_id: str
  container_number: int
  container_count: int

  def __post_init__(self) -> None:
    if not self.document_id:
      raise InvalidWorkerRequestError("document_id must be a non-empty string")
    if self.container_number < 0:
      raise InvalidWorkerRequestError("container_number cannot be negative")
    if self.container_count < 1 or self.container_count > 20:
      raise InvalidWorkerRequestError("container_count must be between 1 and 20")
