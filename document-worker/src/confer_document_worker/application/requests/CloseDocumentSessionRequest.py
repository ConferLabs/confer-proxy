from dataclasses import dataclass

from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest


@dataclass(frozen=True)
class CloseDocumentSessionRequest(DocumentSessionRequest):
  pass
