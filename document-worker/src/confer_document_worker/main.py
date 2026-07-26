import logging
import sys

from confer_document_worker.application.ExtractDocument import ExtractDocument
from confer_document_worker.application.DocumentSession import DocumentSession
from confer_document_worker.application.WorkerApplication import WorkerApplication
from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.domain.RasterizationPolicy import RasterizationPolicy
from confer_document_worker.extraction.DocumentExtractorRouter import DocumentExtractorRouter
from confer_document_worker.extraction.office.DoclingDocumentConverter import DoclingDocumentConverter
from confer_document_worker.extraction.office.DoclingDocumentMapper import DoclingDocumentMapper
from confer_document_worker.extraction.office.DoclingSlimDocumentExtractor import DoclingSlimDocumentExtractor
from confer_document_worker.extraction.office.OfficeArchivePreflight import OfficeArchivePreflight
from confer_document_worker.extraction.office.OfficeExtractionLimits import OfficeExtractionLimits
from confer_document_worker.extraction.pdf.PageClassifier import PageClassifier
from confer_document_worker.extraction.pdf.PdfExtractor import PdfExtractor
from confer_document_worker.extraction.pdf.PdfOcrProcessor import PdfOcrProcessor
from confer_document_worker.extraction.pdf.PdfPageRangeScanner import PdfPageRangeScanner
from confer_document_worker.extraction.pdf.PdfPageScanner import PdfPageScanner
from confer_document_worker.extraction.pdf.TableDetector import TableDetector
from confer_document_worker.extraction.text.TextDocumentExtractor import TextDocumentExtractor
from confer_document_worker.ocr.TesseractOcrBackend import TesseractOcrBackend
from confer_document_worker.ocr.TesseractTsvParser import TesseractTsvParser
from confer_document_worker.protocol.DocumentWorkerRequestDecoder import DocumentWorkerRequestDecoder
from confer_document_worker.protocol.ProtocolCodec import ProtocolCodec
from confer_document_worker.protocol.ProtocolPresenter import ProtocolPresenter
from confer_document_worker.rendering.PageRenderer import PageRenderer
from confer_document_worker.worker.WorkerErrorReporter import WorkerErrorReporter
from confer_document_worker.worker.WorkerProcess import WorkerProcess


def main() -> None:
  arguments = sys.argv[1:]
  check_runtime = arguments == ["--check"]
  if arguments and not check_runtime:
    raise SystemExit("usage: confer-document-worker [--check]")

  # Third-party parsers sometimes include document snippets or filenames in
  # diagnostic logs. Suppress their logging; WorkerErrorReporter emits the
  # content-free diagnostics owned by this process.
  logging.disable(logging.CRITICAL)

  artifact_writer = ArtifactWriter()
  text_extractor = TextDocumentExtractor()
  presenter = ProtocolPresenter()
  rasterization = RasterizationPolicy()
  renderer = PageRenderer(ArtifactReader, rasterization)
  page_scanner = PdfPageScanner(
    PageClassifier(),
    TableDetector(),
  )
  ocr_backend = TesseractOcrBackend(TesseractTsvParser())
  pdf_extractor = PdfExtractor(
    page_scanner,
    PdfPageRangeScanner(page_scanner),
    PdfOcrProcessor(
      ocr_backend,
      rasterization=rasterization,
    ),
  )
  office_limits = OfficeExtractionLimits()
  office_converter = DoclingDocumentConverter(office_limits)
  extractor = DocumentExtractorRouter(
    pdf_extractor,
    DoclingSlimDocumentExtractor(
      OfficeArchivePreflight(),
      office_converter,
      DoclingDocumentMapper(office_limits),
      office_limits,
    ),
    text_extractor,
  )
  application = WorkerApplication(
    ExtractDocument(extractor, artifact_writer),
    DocumentSession(
      ArtifactReader,
      renderer,
      text_extractor,
      artifact_writer,
    ),
  )

  if check_runtime:
    try:
      office_converter.check()
      ocr_backend.check()
    finally:
      application.close()
    return

  WorkerProcess(
    application,
    ProtocolCodec(),
    DocumentWorkerRequestDecoder(),
    presenter,
    WorkerErrorReporter(sys.stderr.buffer),
    sys.stdin.buffer,
    sys.stdout.buffer,
  ).run()


if __name__ == "__main__":
  main()
