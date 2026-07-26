from confer_document_worker.application.DocumentSession import DocumentSession
from confer_document_worker.application.ExtractDocument import ExtractDocument
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest
from confer_document_worker.application.requests.ExtractDocumentRequest import ExtractDocumentRequest
from confer_document_worker.application.requests.WorkerRequest import WorkerRequest
from confer_document_worker.application.responses.WorkerResponse import WorkerResponse


class WorkerApplication:
  def __init__(
    self,
    extract_document: ExtractDocument,
    session: DocumentSession,
  ):
    self.extract_document = extract_document
    self.session = session

  def handle(self, request: WorkerRequest) -> WorkerResponse:
    if isinstance(request, DocumentSessionRequest):
      return self.session.execute(request)
    if isinstance(request, ExtractDocumentRequest):
      return self.extract_document.execute(request)
    raise TypeError("unknown worker request type")

  def close(self) -> None:
    self.session.close()
