from contextlib import ExitStack
from typing import BinaryIO

from confer_document_worker.application.WorkerApplication import WorkerApplication
from confer_document_worker.application.errors.DocumentOperationError import DocumentOperationError
from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.CloseDocumentSessionRequest import CloseDocumentSessionRequest
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest
from confer_document_worker.application.requests.WorkerRequest import WorkerRequest
from confer_document_worker.protocol.DocumentWorkerRequestDecoder import DocumentWorkerRequestDecoder
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolLimits import PROTOCOL_VERSION
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPresenter import ProtocolPresenter
from confer_document_worker.worker.WorkerErrorReporter import WorkerErrorReporter


class WorkerProcess:
  def __init__(
    self,
    application: WorkerApplication,
    codec: ProtocolCodec,
    request_decoder: DocumentWorkerRequestDecoder,
    presenter: ProtocolPresenter,
    error_reporter: WorkerErrorReporter,
    input_stream: BinaryIO,
    output_stream: BinaryIO,
  ):
    self.application = application
    self.codec = codec
    self.request_decoder = request_decoder
    self.presenter = presenter
    self.error_reporter = error_reporter
    self.input_stream = input_stream
    self.output_stream = output_stream

  def run(self) -> None:
    try:
      with self.codec.read(self.input_stream) as message:
        request = self._decode(message)
        if request is None:
          return
        self._handle(request)
        if not isinstance(request, DocumentSessionRequest):
          return
        if isinstance(request, CloseDocumentSessionRequest):
          return

      self._run_session()
    finally:
      self.application.close()

  def _run_session(self) -> None:
    while True:
      with self.codec.read(self.input_stream) as message:
        request = self._decode(message)
        if request is None:
          continue
        if not isinstance(request, DocumentSessionRequest):
          self._write(self._invalid_request("Only document session operations are allowed"))
          continue
        self._handle(request)
        if isinstance(request, CloseDocumentSessionRequest):
          return

  def _decode(self, message: ProtocolMessage) -> WorkerRequest | None:
    try:
      return self.request_decoder.decode(message)
    except InvalidWorkerRequestError as error:
      self._write(self._invalid_request(str(error)))
      return None

  def _handle(self, request: WorkerRequest) -> None:
    with ExitStack() as resources:
      try:
        application_response = resources.enter_context(
          self.application.handle(request),
        )
        response = self.presenter.present(application_response)
      except InvalidWorkerRequestError as error:
        response = self._invalid_request(str(error))
      except DocumentOperationError as error:
        response = self._invalid_request(str(error))
      except Exception as error:
        # Report only code locations and exception types. Standard traceback
        # formatting may expose filenames, extracted content, or local values.
        self.error_reporter.report(request, error)
        response = self._processing_failed()
      self._write(response)

  def _write(self, response: ProtocolMessage) -> None:
    with response:
      self.codec.write(self.output_stream, response)

  def _invalid_request(self, message: str) -> ProtocolMessage:
    return ProtocolMessage(
      {
        "version": PROTOCOL_VERSION,
        "status": "error",
        "error": {"code": "invalid_request", "message": message},
      },
      {},
    )

  def _processing_failed(self) -> ProtocolMessage:
    return ProtocolMessage(
      {
        "version": PROTOCOL_VERSION,
        "status": "error",
        "error": {"code": "processing_failed", "message": "Document processing failed"},
      },
      {},
    )
