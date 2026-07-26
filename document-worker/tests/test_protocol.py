from io import BytesIO
import json

import pytest

from confer_document_worker.application.requests.SearchDocumentRequest import SearchDocumentRequest
from confer_document_worker.domain.SearchQuery import SearchQuery
from confer_document_worker.protocol.DocumentWorkerRequestDecoder import DocumentWorkerRequestDecoder
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolLimits import HEADER_LENGTH
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload


def test_decodes_operation_specific_request() -> None:
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "session_search",
      "document_id": "medical-record",
      "queries": [
        {"all": ["total analyte", "ng/dL"]},
        {"all": ["free analyte", "pg/mL"]},
      ],
      "limit": 12,
      "snippet_characters": 512,
    },
    {},
  )

  request = DocumentWorkerRequestDecoder().decode(message)

  assert isinstance(request, SearchDocumentRequest)
  assert request.document_id == "medical-record"
  assert request.queries == (
    SearchQuery(("total analyte", "ng/dL")),
    SearchQuery(("free analyte", "pg/mL")),
  )
  assert request.limit == 12
  assert request.snippet_characters == 512


def test_defaults_snippet_length_for_older_search_requests() -> None:
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "session_search",
      "document_id": "medical-record",
      "queries": [{"all": ["analyte"]}],
      "limit": 12,
    },
    {},
  )

  request = DocumentWorkerRequestDecoder().decode(message)

  assert isinstance(request, SearchDocumentRequest)
  assert request.snippet_characters == 1_200


def test_rejects_invalid_page_range_before_application_dispatch() -> None:
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "session_read",
      "document_id": "medical-record",
      "container_number": 0,
      "container_count": 0,
    },
    {},
  )

  with pytest.raises(ValueError, match="container_count"):
    DocumentWorkerRequestDecoder().decode(message)


@pytest.mark.parametrize(
  "queries",
  (
    [],
    [{"all": []}],
    [{"all": [""]}],
    [{"all": ["x" * 257]}],
    [{"all": ["match"]}] * 9,
    [{"all": ["match"] * 9}],
    [{"all": ["match"], "unexpected": True}],
  ),
)
def test_rejects_invalid_search_queries(queries: object) -> None:
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "session_search",
      "document_id": "medical-record",
      "queries": queries,
    },
    {},
  )

  with pytest.raises(ValueError):
    DocumentWorkerRequestDecoder().decode(message)


@pytest.mark.parametrize("snippet_characters", (63, 4_001))
def test_rejects_invalid_search_snippet_length(snippet_characters: int) -> None:
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "session_search",
      "document_id": "medical-record",
      "queries": [{"all": ["analyte"]}],
      "snippet_characters": snippet_characters,
    },
    {},
  )

  with pytest.raises(ValueError, match="snippet_characters"):
    DocumentWorkerRequestDecoder().decode(message)


def test_rejects_removed_one_shot_retrieval_operations() -> None:
  message = ProtocolMessage(
    {"version": 1, "operation": "search", "query": "analyte"},
    {},
  )

  with pytest.raises(ValueError, match="Unknown worker operation"):
    DocumentWorkerRequestDecoder().decode(message)


def test_round_trips_binary_payloads() -> None:
  stream = BytesIO()
  expected = ProtocolMessage(
    {"version": 1, "operation": "extract"},
    {
      PayloadRole.SOURCE: ProtocolPayload.from_bytes(
        b"%PDF binary\x00content",
        "application/pdf",
      ),
    },
  )

  codec = ProtocolCodec()
  codec.write(stream, expected)
  encoded = stream.getvalue()
  header_length = HEADER_LENGTH.unpack(encoded[:HEADER_LENGTH.size])[0]
  header = json.loads(encoded[HEADER_LENGTH.size:HEADER_LENGTH.size + header_length])
  assert header["payloads"] == [{
    "role": "source",
    "media_type": "application/pdf",
    "length": len(b"%PDF binary\x00content"),
  }]
  stream.seek(0)
  actual = codec.read(stream)

  assert actual.header == expected.header
  assert actual.payloads[PayloadRole.SOURCE].read_bytes() == expected.payloads[PayloadRole.SOURCE].read_bytes()
  assert actual.payloads[PayloadRole.SOURCE].media_type == "application/pdf"
  assert actual.payloads[PayloadRole.SOURCE].path.exists()
  actual.close()
  expected.close()


def test_rejects_truncated_payload() -> None:
  stream = BytesIO()
  codec = ProtocolCodec()
  codec.write(stream, ProtocolMessage(
    {"version": 1},
    {PayloadRole.RESULT: b"abc"},
  ))
  encoded = stream.getvalue()[:-1]

  with pytest.raises(ValueError, match="truncated"):
    codec.read(BytesIO(encoded))


def test_rejects_negative_direct_receive_length() -> None:
  with pytest.raises(ValueError, match="negative"):
    ProtocolPayload.receive(BytesIO(b"content"), -1, "text/plain")


def test_rejects_media_type_that_does_not_match_role() -> None:
  header = json.dumps({
    "version": 1,
    "payloads": [{"role": "artifact", "media_type": "image/png", "length": 1}],
  }).encode("utf-8")
  encoded = HEADER_LENGTH.pack(len(header)) + header + b"x"

  with pytest.raises(ValueError, match="media type"):
    ProtocolCodec().read(BytesIO(encoded))


def test_rejects_source_media_type_that_does_not_match_request() -> None:
  source = ProtocolPayload.receive(BytesIO(b"content"), 7, "text/plain")
  message = ProtocolMessage(
    {
      "version": 1,
      "operation": "extract",
      "filename": "document.pdf",
      "content_type": "application/pdf",
    },
    {PayloadRole.SOURCE: source},
  )

  try:
    with pytest.raises(ValueError, match="wrong media type"):
      DocumentWorkerRequestDecoder().decode(message)
  finally:
    message.close()


def test_spools_large_payloads_with_bounded_reads() -> None:
  content = b"x" * (2 * 1024 * 1024)
  encoded = BytesIO()
  codec = ProtocolCodec()
  message = ProtocolMessage(
    {"version": 1},
    {PayloadRole.SOURCE: ProtocolPayload.from_bytes(content, "application/pdf")},
  )
  codec.write(encoded, message)
  message.close()
  guarded = GuardedInputStream(encoded.getvalue(), maximum_read=64 * 1024)

  received = codec.read(guarded)

  assert received.payloads[PayloadRole.SOURCE].read_bytes() == content
  assert guarded.maximum_observed_read <= 64 * 1024
  received.close()


class GuardedInputStream(BytesIO):
  def __init__(self, content: bytes, maximum_read: int):
    super().__init__(content)
    self.maximum_read = maximum_read
    self.maximum_observed_read = 0

  def read(self, size: int = -1) -> bytes:
    if size < 0 or size > self.maximum_read:
      raise AssertionError(f"unbounded protocol read requested: {size}")
    self.maximum_observed_read = max(self.maximum_observed_read, size)
    return super().read(size)
