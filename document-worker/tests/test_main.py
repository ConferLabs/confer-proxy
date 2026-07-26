import os
from io import BytesIO
import json
import subprocess
import sys

from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload
from confer_document_worker.worker.WorkerErrorReporter import WorkerErrorReporter
from test_extractor import native_pdf

WORKER_COMMAND = (sys.executable, "-S", "-m", "confer_document_worker.main")


def test_production_composition_processes_an_extract_request() -> None:
  codec = ProtocolCodec()
  encoded_request = BytesIO()
  codec.write(encoded_request, ProtocolMessage(
    {
      "version": 1,
      "operation": "extract",
      "filename": "sample.pdf",
      "content_type": "application/pdf",
    },
    {
      PayloadRole.SOURCE: ProtocolPayload.from_bytes(
        native_pdf(),
        "application/pdf",
      ),
    },
  ))

  completed = subprocess.run(
    WORKER_COMMAND,
    input=encoded_request.getvalue(),
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    check=False,
    timeout=30,
  )

  assert completed.returncode == 0
  assert completed.stderr == b""
  response = codec.read(BytesIO(completed.stdout))
  assert response.header["status"] == "ok"
  assert b"Analyte" in response.payloads[PayloadRole.TEXT].read_bytes()
  response.close()


def test_production_composition_reports_content_safe_errors() -> None:
  codec = ProtocolCodec()
  encoded_request = BytesIO()
  codec.write(encoded_request, ProtocolMessage(
    {
      "version": 1,
      "operation": "extract",
      "filename": "private-filename.bin",
      "content_type": "application/x-private-document",
    },
    {
      PayloadRole.SOURCE: ProtocolPayload.from_bytes(
        b"private document content",
        "application/x-private-document",
      ),
    },
  ))

  completed = subprocess.run(
    WORKER_COMMAND,
    input=encoded_request.getvalue(),
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    check=False,
    timeout=30,
  )

  assert completed.returncode == 0
  response = codec.read(BytesIO(completed.stdout))
  assert response.header["error"] == {
    "code": "processing_failed",
    "message": "Document processing failed",
  }
  diagnostic = completed.stderr
  report = json.loads(diagnostic.removeprefix(WorkerErrorReporter.PREFIX))
  assert report["request"] == "ExtractDocumentRequest"
  assert report["exception_chain"][0]["type"] == "builtins.ValueError"
  assert report["exception_chain"][0]["frames"][-1].startswith(
    "confer_document_worker.extraction.DocumentExtractorRouter."
    "DocumentExtractorRouter.extract:",
  )
  assert b"private-filename.bin" not in diagnostic
  assert b"private document content" not in diagnostic
  assert b"document media type is not supported" not in diagnostic
  assert b"Traceback" not in diagnostic
  response.close()


def test_production_runtime_passes_its_self_check() -> None:
  completed = subprocess.run(
    (*WORKER_COMMAND, "--check"),
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    check=False,
    timeout=30,
  )

  assert completed.returncode == 0
  assert completed.stdout == b""
  assert completed.stderr == b""


def test_production_runtime_self_check_requires_ocr_executable() -> None:
  completed = subprocess.run(
    (*WORKER_COMMAND, "--check"),
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    check=False,
    timeout=30,
    env={**os.environ, "PATH": ""},
  )

  assert completed.returncode != 0
  assert b"OCR executable could not be started" in completed.stderr
