from dataclasses import dataclass

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest
from confer_document_worker.domain.DocumentSource import DocumentSource


@dataclass(frozen=True)
class RenderDocumentPageRequest(DocumentSessionRequest):
  document_id: str
  source: DocumentSource
  container_number: int
  dpi: int

  def __post_init__(self) -> None:
    if not self.document_id:
      raise InvalidWorkerRequestError("document_id must be a non-empty string")
    if self.container_number < 0:
      raise InvalidWorkerRequestError("container_number cannot be negative")
    if self.dpi < 72 or self.dpi > 300:
      raise InvalidWorkerRequestError("render DPI is outside the allowed range")
