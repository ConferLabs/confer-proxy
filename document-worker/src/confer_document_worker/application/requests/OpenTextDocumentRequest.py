from dataclasses import dataclass

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest
from confer_document_worker.domain.DocumentSource import DocumentSource


@dataclass(frozen=True)
class OpenTextDocumentRequest(DocumentSessionRequest):
  document_id: str
  filename: str
  source: DocumentSource

  def __post_init__(self) -> None:
    if not self.document_id:
      raise InvalidWorkerRequestError("document_id must be a non-empty string")
    if not self.filename:
      raise InvalidWorkerRequestError("filename must be a non-empty string")
