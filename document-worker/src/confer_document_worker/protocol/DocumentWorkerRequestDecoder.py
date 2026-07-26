from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.CloseDocumentSessionRequest import CloseDocumentSessionRequest
from confer_document_worker.application.requests.ExtractDocumentRequest import ExtractDocumentRequest
from confer_document_worker.application.requests.GetDocumentOverviewRequest import GetDocumentOverviewRequest
from confer_document_worker.application.requests.OpenDocumentRequest import OpenDocumentRequest
from confer_document_worker.application.requests.OpenTextDocumentRequest import OpenTextDocumentRequest
from confer_document_worker.application.requests.ReadDocumentRequest import ReadDocumentRequest
from confer_document_worker.application.requests.ReleaseDocumentRequest import ReleaseDocumentRequest
from confer_document_worker.application.requests.RenderDocumentPageRequest import RenderDocumentPageRequest
from confer_document_worker.application.requests.SearchDocumentRequest import SearchDocumentRequest
from confer_document_worker.application.requests.WorkerRequest import WorkerRequest
from confer_document_worker.domain.SearchQuery import SearchQuery
from confer_document_worker.protocol.DocumentWorkerOperation import DocumentWorkerOperation
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolLimits import PROTOCOL_VERSION
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.RequestParameters import RequestParameters


class DocumentWorkerRequestDecoder:
  def decode(self, message: ProtocolMessage) -> WorkerRequest:
    if message.header.get("version") != PROTOCOL_VERSION:
      raise InvalidWorkerRequestError("Unsupported worker protocol version")

    operation = self._operation(message)
    parameters = RequestParameters(message)
    if operation is DocumentWorkerOperation.EXTRACT:
      content_type = parameters.string("content_type")
      return ExtractDocumentRequest(
        parameters.source(PayloadRole.SOURCE, content_type),
        parameters.string("filename"),
        content_type,
      )
    if operation is DocumentWorkerOperation.OPEN:
      return OpenDocumentRequest(
        parameters.string("document_id"),
        parameters.payload(PayloadRole.ARTIFACT).path,
      )
    if operation is DocumentWorkerOperation.OPEN_TEXT:
      return OpenTextDocumentRequest(
        parameters.string("document_id"),
        parameters.string("filename"),
        parameters.source(PayloadRole.TEXT, PayloadRole.TEXT.media_type),
      )
    if operation is DocumentWorkerOperation.OVERVIEW:
      return GetDocumentOverviewRequest(parameters.string("document_id"))
    if operation is DocumentWorkerOperation.SEARCH:
      return SearchDocumentRequest(
        parameters.string("document_id"),
        self._search_queries(parameters),
        parameters.integer("limit", 8),
        parameters.integer(
          "snippet_characters",
          SearchDocumentRequest.DEFAULT_SNIPPET_CHARACTERS,
        ),
      )
    if operation is DocumentWorkerOperation.READ:
      return ReadDocumentRequest(
        parameters.string("document_id"),
        parameters.integer("container_number"),
        parameters.integer("container_count", 1),
      )
    if operation is DocumentWorkerOperation.RENDER:
      return RenderDocumentPageRequest(
        parameters.string("document_id"),
        parameters.source(PayloadRole.SOURCE, "application/pdf"),
        parameters.integer("container_number"),
        parameters.integer("dpi", 160),
      )
    if operation is DocumentWorkerOperation.RELEASE:
      return ReleaseDocumentRequest(parameters.string("document_id"))
    if operation is DocumentWorkerOperation.CLOSE:
      return CloseDocumentSessionRequest()
    raise InvalidWorkerRequestError("Unknown worker operation")

  def _search_queries(self, parameters: RequestParameters) -> tuple[SearchQuery, ...]:
    queries: list[SearchQuery] = []
    for value in parameters.object_array("queries"):
      if set(value) != {"all"}:
        raise InvalidWorkerRequestError("search queries may contain only all")
      terms = value.get("all")
      if not isinstance(terms, list) or any(not isinstance(term, str) for term in terms):
        raise InvalidWorkerRequestError("all must be an array of strings")
      try:
        queries.append(SearchQuery(tuple(terms)))
      except ValueError as error:
        raise InvalidWorkerRequestError(str(error)) from error
    return tuple(queries)

  def _operation(self, message: ProtocolMessage) -> DocumentWorkerOperation:
    operation_name = message.header.get("operation")
    if not isinstance(operation_name, str):
      raise InvalidWorkerRequestError("Worker operation is required")
    try:
      return DocumentWorkerOperation(operation_name)
    except ValueError as error:
      raise InvalidWorkerRequestError("Unknown worker operation") from error
