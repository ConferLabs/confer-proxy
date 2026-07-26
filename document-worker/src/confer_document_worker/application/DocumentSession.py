from collections.abc import Callable
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import BinaryIO, cast

from confer_document_worker.application.errors.DocumentOperationError import DocumentOperationError
from confer_document_worker.application.requests.CloseDocumentSessionRequest import CloseDocumentSessionRequest
from confer_document_worker.application.requests.GetDocumentOverviewRequest import GetDocumentOverviewRequest
from confer_document_worker.application.requests.OpenDocumentRequest import OpenDocumentRequest
from confer_document_worker.application.requests.OpenTextDocumentRequest import OpenTextDocumentRequest
from confer_document_worker.application.requests.ReadDocumentRequest import ReadDocumentRequest
from confer_document_worker.application.requests.ReleaseDocumentRequest import ReleaseDocumentRequest
from confer_document_worker.application.requests.RenderDocumentPageRequest import RenderDocumentPageRequest
from confer_document_worker.application.requests.SearchDocumentRequest import SearchDocumentRequest
from confer_document_worker.application.requests.WorkerRequest import WorkerRequest
from confer_document_worker.application.responses.DocumentOverviewResult import DocumentOverviewResult
from confer_document_worker.application.responses.DocumentReadResult import DocumentReadResult
from confer_document_worker.application.responses.DocumentSearchResult import DocumentSearchResult
from confer_document_worker.application.responses.EmptyResponse import EmptyResponse
from confer_document_worker.application.responses.RenderDocumentPageResult import RenderDocumentPageResult
from confer_document_worker.application.responses.WorkerResponse import WorkerResponse
from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.extraction.text.TextDocumentExtractor import TextDocumentExtractor
from confer_document_worker.rendering.PageRenderer import PageRenderer


class DocumentSession:
  MAX_DOCUMENTS = 1_000
  MAX_INLINE_CONTENT_BYTES = 24_000

  def __init__(
    self,
    reader_factory: Callable[[bytes | Path], ArtifactReader],
    renderer: PageRenderer,
    text_extractor: TextDocumentExtractor,
    artifact_writer: ArtifactWriter,
  ):
    self.reader_factory = reader_factory
    self.renderer = renderer
    self.text_extractor = text_extractor
    self.artifact_writer = artifact_writer
    self.readers: dict[str, ArtifactReader] = {}

  def execute(self, request: WorkerRequest) -> WorkerResponse:
    if isinstance(request, OpenDocumentRequest):
      return self._open(request)
    if isinstance(request, OpenTextDocumentRequest):
      return self._open_text(request)
    if isinstance(request, GetDocumentOverviewRequest):
      return self._overview(request)
    if isinstance(request, SearchDocumentRequest):
      return self._search(request)
    if isinstance(request, ReadDocumentRequest):
      return self._read(request)
    if isinstance(request, RenderDocumentPageRequest):
      return self._render(request)
    if isinstance(request, ReleaseDocumentRequest):
      return self._release(request)
    if isinstance(request, CloseDocumentSessionRequest):
      self.close()
      return EmptyResponse()
    raise TypeError("unknown document session request type")

  def close(self) -> None:
    readers = tuple(self.readers.values())
    self.readers.clear()
    for reader in readers:
      reader.close()

  def _open(self, request: OpenDocumentRequest) -> EmptyResponse:
    self._require_open_capacity(request.document_id)
    try:
      reader = self.reader_factory(request.artifact)
    except (OSError, ValueError) as error:
      raise DocumentOperationError("Document artifact is invalid") from error
    self.readers[request.document_id] = reader
    return EmptyResponse()

  def _open_text(self, request: OpenTextDocumentRequest) -> EmptyResponse:
    self._require_open_capacity(request.document_id)
    try:
      content = self.text_extractor.extract(
        request.source,
        request.filename,
        "text/plain",
      )
      with NamedTemporaryFile(prefix="confer-legacy-artifact-", suffix=".bin") as artifact_file:
        self.artifact_writer.write(content, cast(BinaryIO, artifact_file))
        reader = self.reader_factory(Path(artifact_file.name))
    except (OSError, ValueError) as error:
      raise DocumentOperationError("Legacy document text is invalid") from error
    self.readers[request.document_id] = reader
    return EmptyResponse()

  def _require_open_capacity(self, document_id: str) -> None:
    if document_id in self.readers:
      raise DocumentOperationError("Document is already open")
    if len(self.readers) >= self.MAX_DOCUMENTS:
      raise DocumentOperationError("Document session capacity exceeded")

  def _overview(self, request: GetDocumentOverviewRequest) -> DocumentOverviewResult:
    reader = self._reader(request.document_id)
    metadata = reader.metadata()
    return DocumentOverviewResult(
      metadata=metadata,
      outline=reader.outline(limit=500),
      labeled_containers=reader.labeled_containers(),
      first_chunk_id=reader.first_chunk_id(),
      content=reader.full_text_if_small(self.MAX_INLINE_CONTENT_BYTES),
    )

  def _search(self, request: SearchDocumentRequest) -> DocumentSearchResult:
    try:
      fetched = self._reader(request.document_id).search(
        request.queries,
        request.limit + 1,
        request.snippet_characters,
      )
    except ValueError as error:
      raise DocumentOperationError("Search query has no searchable terms") from error
    return DocumentSearchResult(
      hits=fetched[:request.limit],
      has_more=len(fetched) > request.limit,
    )

  def _read(self, request: ReadDocumentRequest) -> DocumentReadResult:
    reader = self._reader(request.document_id)
    try:
      window = reader.read_containers(
        request.container_number,
        request.container_count,
      )
    except KeyError as error:
      raise DocumentOperationError("Document read target was not found") from error
    except ValueError as error:
      raise DocumentOperationError("Document read is invalid") from error
    return DocumentReadResult(window)

  def _render(self, request: RenderDocumentPageRequest) -> RenderDocumentPageResult:
    try:
      rendered = self.renderer.render_from_reader(
        request.source,
        self._reader(request.document_id),
        request.container_number,
        request.dpi,
      )
    except (OSError, ValueError) as error:
      raise DocumentOperationError("Document page could not be rendered") from error
    return RenderDocumentPageResult(
      rendered.png,
      rendered.width,
      rendered.height,
      rendered.container_number,
    )

  def _release(self, request: ReleaseDocumentRequest) -> EmptyResponse:
    reader = self.readers.pop(request.document_id, None)
    if reader is not None:
      reader.close()
    return EmptyResponse()

  def _reader(self, document_id: str) -> ArtifactReader:
    reader = self.readers.get(document_id)
    if reader is None:
      raise DocumentOperationError("Document is not open")
    return reader
