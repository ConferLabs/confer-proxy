from io import BytesIO
import json

from confer_document_worker.application.errors.DocumentOperationError import DocumentOperationError
from confer_document_worker.application.responses.EmptyResponse import EmptyResponse
from confer_document_worker.protocol.DocumentWorkerRequestDecoder import DocumentWorkerRequestDecoder
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload
from confer_document_worker.protocol.ProtocolPresenter import ProtocolPresenter
from confer_document_worker.worker.WorkerErrorReporter import WorkerErrorReporter
from confer_document_worker.worker.WorkerProcess import WorkerProcess


class RecordingApplication:
  def __init__(self, failure: Exception | None = None):
    self.failure = failure
    self.requests: list[object] = []
    self.closed = False

  def handle(self, request: object) -> EmptyResponse:
    self.requests.append(request)
    if self.failure is not None:
      raise self.failure
    return EmptyResponse()

  def close(self) -> None:
    self.closed = True


def encode_requests(*requests: ProtocolMessage) -> bytes:
  output = BytesIO()
  codec = ProtocolCodec()
  for request in requests:
    try:
      codec.write(output, request)
    finally:
      request.close()
  return output.getvalue()


def run_worker(
  application: RecordingApplication,
  encoded: bytes,
  errors: BytesIO | None = None,
) -> BytesIO:
  output = BytesIO()
  WorkerProcess(
    application,
    ProtocolCodec(),
    DocumentWorkerRequestDecoder(),
    ProtocolPresenter(),
    WorkerErrorReporter(errors if errors is not None else BytesIO()),
    BytesIO(encoded),
    output,
  ).run()
  output.seek(0)
  return output


def extract_request() -> ProtocolMessage:
  return ProtocolMessage(
    {
      "version": 1,
      "operation": "extract",
      "filename": "document.txt",
      "content_type": "text/plain",
    },
    {
      PayloadRole.SOURCE: ProtocolPayload.from_bytes(b"content", "text/plain"),
    },
  )


def test_rejects_invalid_initial_request_without_entering_a_session() -> None:
  application = RecordingApplication()
  encoded = encode_requests(ProtocolMessage(
    {"version": 2, "operation": "session_close"},
    {},
  ))

  output = run_worker(application, encoded)
  response = ProtocolCodec().read(output)

  assert response.header["status"] == "error"
  assert response.header["error"]["code"] == "invalid_request"
  assert application.requests == []
  assert application.closed
  response.close()


def test_session_rejects_one_shot_operation_and_continues_to_close() -> None:
  application = RecordingApplication()
  encoded = encode_requests(
    ProtocolMessage(
      {"version": 1, "operation": "session_release", "document_id": "document"},
      {},
    ),
    extract_request(),
    ProtocolMessage({"version": 1, "operation": "session_close"}, {}),
  )

  output = run_worker(application, encoded)
  codec = ProtocolCodec()
  responses = tuple(codec.read(output) for _ in range(3))

  assert [response.header["status"] for response in responses] == ["ok", "error", "ok"]
  assert responses[1].header["error"]["message"] == "Only document session operations are allowed"
  assert len(application.requests) == 2
  assert application.closed
  for response in responses:
    response.close()


def test_unexpected_errors_do_not_expose_exception_messages() -> None:
  errors = BytesIO()
  application = RecordingApplication(ValueError("private document content"))

  output = run_worker(application, encode_requests(extract_request()), errors)
  response = ProtocolCodec().read(output)

  assert response.header["error"] == {
    "code": "processing_failed",
    "message": "Document processing failed",
  }
  assert "private document content" not in str(response.header)
  diagnostic = errors.getvalue()
  report = json.loads(diagnostic.removeprefix(WorkerErrorReporter.PREFIX))
  assert report["request"] == "ExtractDocumentRequest"
  assert report["exception_chain"][0]["type"] == "builtins.ValueError"
  assert report["exception_chain"][0]["frames"][-1].startswith(
    "test_worker_process.RecordingApplication.handle:",
  )
  assert b"private document content" not in diagnostic
  assert b"Traceback" not in diagnostic
  assert application.closed
  response.close()


def test_document_operation_errors_are_safe_to_report() -> None:
  application = RecordingApplication(DocumentOperationError("Document is not open"))

  output = run_worker(application, encode_requests(extract_request()))
  response = ProtocolCodec().read(output)

  assert response.header["error"]["message"] == "Document is not open"
  assert application.closed
  response.close()
