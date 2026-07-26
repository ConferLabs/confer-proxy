import json

from confer_document_worker.application.responses.DocumentOverviewResult import DocumentOverviewResult
from confer_document_worker.application.responses.DocumentReadResult import DocumentReadResult
from confer_document_worker.application.responses.DocumentSearchResult import DocumentSearchResult
from confer_document_worker.application.responses.EmptyResponse import EmptyResponse
from confer_document_worker.application.responses.ExtractDocumentResult import ExtractDocumentResult
from confer_document_worker.application.responses.RenderDocumentPageResult import RenderDocumentPageResult
from confer_document_worker.application.responses.WorkerResponse import WorkerResponse
from confer_document_worker.artifact.ArtifactMetadata import ArtifactMetadata
from confer_document_worker.domain.ContainerReference import ContainerReference
from confer_document_worker.domain.OutlineEntry import OutlineEntry
from confer_document_worker.domain.RegionReference import RegionReference
from confer_document_worker.domain.SearchHit import SearchHit
from confer_document_worker.domain.TextWindow import TextWindow
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolLimits import PROTOCOL_VERSION
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload


class ProtocolPresenter:
  def __init__(self, max_outline_bytes: int = 30_000):
    self.max_outline_bytes = max_outline_bytes

  def present(self, response: WorkerResponse) -> ProtocolMessage:
    if isinstance(response, EmptyResponse):
      return self._message()
    if isinstance(response, ExtractDocumentResult):
      return self._extraction(response)
    if isinstance(response, DocumentOverviewResult):
      return self._overview(response)
    if isinstance(response, DocumentSearchResult):
      return self._json_message({
        "hits": [self._hit(hit) for hit in response.hits],
        "has_more": response.has_more,
      })
    if isinstance(response, DocumentReadResult):
      return self._message(
        result=self._window(response.window),
        payloads={PayloadRole.TEXT: response.window.text.encode("utf-8")},
      )
    if isinstance(response, RenderDocumentPageResult):
      return self._message(
        result={
          "media_type": "image/png",
          "width": response.width,
          "height": response.height,
          "container_number": response.container_number,
        },
        payloads={PayloadRole.IMAGE: response.png},
      )
    raise TypeError("unknown worker response type")

  def bounded_outline(
    self,
    entries: tuple[OutlineEntry, ...],
  ) -> tuple[OutlineEntry, ...]:
    selected: list[OutlineEntry] = []
    encoded_bytes = 0
    for entry in entries:
      entry_bytes = len(self._json(self._outline_entry(entry))) + 1
      if encoded_bytes + entry_bytes > self.max_outline_bytes:
        break
      selected.append(entry)
      encoded_bytes += entry_bytes
    return tuple(selected)

  def _extraction(self, response: ExtractDocumentResult) -> ProtocolMessage:
    text = response.text.decode("utf-8")
    result: dict[str, object] = {
      "text_bytes": len(response.text),
      "text_characters": len(text.encode("utf-16-le")) // 2,
      "json_escaped_text_bytes": len(self._json(text)) - 2,
    }
    artifact = ProtocolPayload.from_file(
      response.artifact,
      response.artifact_length,
      PayloadRole.ARTIFACT.media_type,
    )
    return self._message(
      result=result,
      payloads={
        PayloadRole.ARTIFACT: artifact,
        PayloadRole.TEXT: response.text,
      },
    )

  def _overview(self, response: DocumentOverviewResult) -> ProtocolMessage:
    outline = self.bounded_outline(response.outline)
    result: dict[str, object] = {
      "metadata": self._metadata(response.metadata),
      "outline": [self._outline_entry(entry) for entry in outline],
      "outline_truncated": len(outline) < response.metadata.outline_count,
      "labeled_containers": [
        self._container(container) for container in response.labeled_containers
      ],
      "first_chunk_id": response.first_chunk_id,
    }
    if response.content is not None:
      result["content"] = response.content
    return self._json_message(result)

  def _json_message(self, value: object) -> ProtocolMessage:
    return self._message(payloads={PayloadRole.RESULT: self._json(value)})

  def _message(
    self,
    result: dict[str, object] | None = None,
    payloads: dict[PayloadRole, bytes | ProtocolPayload] | None = None,
  ) -> ProtocolMessage:
    header: dict[str, object] = {
      "version": PROTOCOL_VERSION,
      "status": "ok",
    }
    if result is not None:
      header["result"] = result
    return ProtocolMessage(header, payloads or {})

  def _metadata(self, metadata: ArtifactMetadata) -> dict[str, object]:
    result = {
      "schema_version": metadata.schema_version,
      "source_sha256": metadata.source_sha256,
      "media_type": metadata.media_type,
      "title": metadata.title or "",
      "text_bytes": metadata.text_bytes,
      "container_count": metadata.container_count,
      "outline_count": metadata.outline_count,
    }
    if metadata.page_count is not None:
      result["page_count"] = metadata.page_count
    return result

  def _hit(self, hit: SearchHit) -> dict[str, object]:
    return {
      "chunk_id": hit.chunk_id,
      "ordinal": hit.ordinal,
      "score": hit.score,
      "container_start": hit.container_start,
      "container_end": hit.container_end,
      "text": hit.text,
      "regions": [self._region(region) for region in hit.regions],
      "containers": [self._container(container) for container in hit.containers],
    }

  def _window(self, window: TextWindow) -> dict[str, object]:
    return {
      "container_start": window.container_start,
      "container_end": window.container_end,
      "total_containers": window.total_containers,
      "regions": [self._region(region) for region in window.regions],
      "containers": [self._container(container) for container in window.containers],
    }

  def _region(self, region: RegionReference) -> dict[str, object]:
    return {
      "id": region.id,
      "container_number": region.container_number,
      "type": region.type.value,
      "confidence": region.confidence,
      "renderable": region.renderable,
    }

  def _container(self, container: ContainerReference) -> dict[str, object]:
    return {
      "number": container.number,
      "kind": container.kind.value,
      "label": container.label,
    }

  def _outline_entry(self, entry: OutlineEntry) -> dict[str, object]:
    return {
      "level": entry.level,
      "title": entry.title,
      "container_number": entry.container_number,
    }

  def _json(self, value: object) -> bytes:
    return json.dumps(
      value,
      ensure_ascii=False,
      separators=(",", ":"),
    ).encode("utf-8")
