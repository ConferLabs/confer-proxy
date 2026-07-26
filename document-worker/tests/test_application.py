import hashlib
import json
from io import BytesIO

from confer_document_worker.application.DocumentSession import DocumentSession
from confer_document_worker.application.ExtractDocument import ExtractDocument
from confer_document_worker.application.WorkerApplication import WorkerApplication
from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.OutlineEntry import OutlineEntry
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.extraction.DocumentExtractorRouter import DocumentExtractorRouter
from confer_document_worker.extraction.office.DoclingDocumentConverter import DoclingDocumentConverter
from confer_document_worker.extraction.office.DoclingDocumentMapper import DoclingDocumentMapper
from confer_document_worker.extraction.office.DoclingSlimDocumentExtractor import DoclingSlimDocumentExtractor
from confer_document_worker.extraction.office.OfficeArchivePreflight import OfficeArchivePreflight
from confer_document_worker.extraction.office.OfficeExtractionLimits import OfficeExtractionLimits
from confer_document_worker.extraction.text.TextDocumentExtractor import TextDocumentExtractor
from confer_document_worker.ocr.TesseractOcrBackend import TesseractOcrBackend
from confer_document_worker.ocr.TesseractTsvParser import TesseractTsvParser
from confer_document_worker.protocol.DocumentWorkerRequestDecoder import DocumentWorkerRequestDecoder
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload
from confer_document_worker.protocol.ProtocolPresenter import ProtocolPresenter
from confer_document_worker.rendering.PageRenderer import PageRenderer
from test_extractor import native_pdf
from test_extractor import pdf_extractor


def request(
  operation: str,
  payloads: dict[PayloadRole, bytes],
  **options: object,
) -> ProtocolMessage:
  return ProtocolMessage(
    {"version": 1, "operation": operation, **options},
    {
      role: ProtocolPayload.receive(
        BytesIO(payload),
        len(payload),
        str(options.get("content_type", "application/pdf"))
        if role is PayloadRole.SOURCE else role.media_type,
      )
      for role, payload in payloads.items()
    },
  )


def handle(
  application: WorkerApplication,
  operation: str,
  payloads: dict[PayloadRole, bytes],
  **options: object,
) -> ProtocolMessage:
  message = request(operation, payloads, **options)
  response = None
  presented = None
  try:
    response = application.handle(DocumentWorkerRequestDecoder().decode(message))
    presented = ProtocolPresenter().present(response)
    encoded = BytesIO()
    codec = ProtocolCodec()
    codec.write(encoded, presented)
    encoded.seek(0)
    return codec.read(encoded)
  finally:
    if presented is not None:
      presented.close()
    if response is not None:
      response.close()
    message.close()


def worker_application() -> WorkerApplication:
  artifact_writer = ArtifactWriter()
  text_extractor = TextDocumentExtractor()
  office_limits = OfficeExtractionLimits()
  extractor = DocumentExtractorRouter(
    pdf_extractor(TesseractOcrBackend(TesseractTsvParser())),
    DoclingSlimDocumentExtractor(
      OfficeArchivePreflight(),
      DoclingDocumentConverter(office_limits),
      DoclingDocumentMapper(office_limits),
      office_limits,
    ),
    text_extractor,
  )
  renderer = PageRenderer(ArtifactReader)
  return WorkerApplication(
    ExtractDocument(extractor, artifact_writer),
    DocumentSession(
      ArtifactReader,
      renderer,
      text_extractor,
      artifact_writer,
    ),
  )


def extract_pdf(application: WorkerApplication) -> tuple[bytes, bytes]:
  pdf = native_pdf()
  extracted = handle(
    application,
    "extract",
    {PayloadRole.SOURCE: pdf},
    filename="sample.pdf",
    content_type="application/pdf",
  )
  try:
    assert extracted.header["status"] == "ok"
    text = extracted.payloads[PayloadRole.TEXT].read_bytes()
    assert b"Analyte" in text
    assert extracted.header["result"]["text_bytes"] == len(text)
    assert extracted.header["result"]["text_characters"] == len(
      text.decode("utf-8").encode("utf-16-le")
    ) // 2
    assert extracted.header["result"]["json_escaped_text_bytes"] == len(
      json.dumps(
        text.decode("utf-8"),
        ensure_ascii=False,
        separators=(",", ":"),
      ).encode("utf-8")
    ) - 2
    return pdf, extracted.payloads[PayloadRole.ARTIFACT].read_bytes()
  finally:
    extracted.close()


def test_extract_and_session_retrieval_flow() -> None:
  application = worker_application()
  pdf, artifact = extract_pdf(application)

  opened = handle(
    application,
    "session_open",
    {PayloadRole.ARTIFACT: artifact},
    document_id="medical-record",
  )
  assert opened.header["status"] == "ok"
  assert opened.payloads == {}
  opened.close()

  overview = handle(
    application,
    "session_overview",
    {},
    document_id="medical-record",
  )
  overview_json = json.loads(overview.payloads[PayloadRole.RESULT].read_bytes())
  assert overview_json["metadata"]["page_count"] == 1
  assert overview_json["labeled_containers"] == []
  assert overview_json["first_chunk_id"] == 1
  assert "Analyte" in overview_json["content"]
  overview.close()

  searched = handle(
    application,
    "session_search",
    {},
    document_id="medical-record",
    queries=[{"all": ["analyte"]}],
    limit=5,
  )
  search_result = json.loads(searched.payloads[PayloadRole.RESULT].read_bytes())
  hits = search_result["hits"]
  assert search_result["has_more"] is False
  assert hits
  assert "Analyte" in hits[0]["text"]
  assert hits[0]["containers"][0]["kind"] == "page"
  assert any(region["type"] == "table" for region in hits[0]["regions"])
  assert all(region["renderable"] for region in hits[0]["regions"])
  searched.close()

  read = handle(
    application,
    "session_read",
    {},
    document_id="medical-record",
    container_number=0,
    container_count=1,
  )
  assert b"Analyte" in read.payloads[PayloadRole.TEXT].read_bytes()
  assert read.header["result"]["container_start"] == 0
  assert read.header["result"]["container_end"] == 0
  assert read.header["result"]["total_containers"] == 1
  read.close()

  rendered = handle(
    application,
    "session_render",
    {PayloadRole.SOURCE: pdf},
    document_id="medical-record",
    container_number=0,
    dpi=160,
  )
  assert rendered.header["result"]["media_type"] == "image/png"
  assert rendered.payloads[PayloadRole.IMAGE].read_bytes().startswith(b"\x89PNG")
  rendered.close()

  released = handle(
    application,
    "session_release",
    {},
    document_id="medical-record",
  )
  assert released.header["status"] == "ok"
  released.close()

  closed = handle(application, "session_close", {})
  assert closed.header["status"] == "ok"
  closed.close()


def test_outline_budget_is_measured_in_serialized_utf8_bytes() -> None:
  oversized = (OutlineEntry(1, "é" * 20_000, 0),)

  assert ProtocolPresenter().bounded_outline(oversized) == ()


def test_search_has_more_uses_an_exact_lookahead_at_the_maximum_limit() -> None:
  source = b"many matching chunks"
  document = DocumentContent(
    source_sha256=hashlib.sha256(source).hexdigest(),
    containers=(
      DocumentContainer(
        number=0,
        width=1,
        height=60,
        blocks=tuple(
          TextBlock(f"needle result {index} " + "x" * 220, Bounds(0, index, 1, index + 1))
          for index in range(60)
        ),
      ),
    ),
  )
  artifact = ArtifactWriter(chunk_bytes=256, overlap_bytes=0).build(document)
  application = worker_application()
  opened = handle(
    application,
    "session_open",
    {PayloadRole.ARTIFACT: artifact},
    document_id="many-results",
  )
  opened.close()

  searched = handle(
    application,
    "session_search",
    {},
    document_id="many-results",
    queries=[{"all": ["needle"]}],
    limit=50,
    snippet_characters=64,
  )
  search_result = json.loads(searched.payloads[PayloadRole.RESULT].read_bytes())

  assert len(search_result["hits"]) == 50
  assert all(len(hit["text"]) <= 64 for hit in search_result["hits"])
  assert search_result["has_more"] is True
  searched.close()


def test_session_can_index_legacy_extracted_text() -> None:
  application = worker_application()
  opened = handle(
    application,
    "session_open_text",
    {PayloadRole.TEXT: b"Analyte 410 ng/dL\nReference range 250-827"},
    document_id="legacy-record",
    filename="legacy.pdf",
  )
  assert opened.header["status"] == "ok"
  assert opened.payloads == {}
  opened.close()

  overview = handle(
    application,
    "session_overview",
    {},
    document_id="legacy-record",
  )
  assert "Analyte" in json.loads(
    overview.payloads[PayloadRole.RESULT].read_bytes()
  )["content"]
  overview.close()

  searched = handle(
    application,
    "session_search",
    {},
    document_id="legacy-record",
    queries=[{"all": ["analyte"]}],
  )
  assert json.loads(searched.payloads[PayloadRole.RESULT].read_bytes())["hits"]
  searched.close()
