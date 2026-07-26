from dataclasses import dataclass

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest


@dataclass(frozen=True)
class GetDocumentOverviewRequest(DocumentSessionRequest):
  document_id: str

  def __post_init__(self) -> None:
    if not self.document_id:
      raise InvalidWorkerRequestError("document_id must be a non-empty string")
