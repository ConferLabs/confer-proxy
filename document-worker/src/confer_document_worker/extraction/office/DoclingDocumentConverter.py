from pathlib import Path
from typing import Any

from confer_document_worker.extraction.MediaTypes import (
  DOCX_MEDIA_TYPE,
  HTML_MEDIA_TYPE,
  MARKDOWN_MEDIA_TYPES,
  PPTX_MEDIA_TYPE,
  STRUCTURED_MEDIA_TYPES,
  XLSX_MEDIA_TYPE,
)
from confer_document_worker.extraction.office.DocumentPathAlias import DocumentPathAlias
from confer_document_worker.extraction.office.OfficeExtractionLimits import OfficeExtractionLimits


class DoclingDocumentConverter:
  def __init__(self, limits: OfficeExtractionLimits):
    self.limits = limits

  def check(self) -> None:
    for media_type in STRUCTURED_MEDIA_TYPES:
      self._backend(media_type)

  def convert(self, source: Path, filename: str, media_type: str) -> Any:
    backend_type, input_format = self._backend(media_type)
    extension = f".{input_format.value}"
    with DocumentPathAlias(source, extension) as parser_source:
      return self._convert(parser_source, filename, backend_type, input_format)

  def _backend(self, media_type: str) -> tuple[type[Any], Any]:
    # Keep Docling and its transitive imports off the latency-critical PDF path.
    from docling.datamodel.base_models import InputFormat

    if media_type == DOCX_MEDIA_TYPE:
      from docling.backend.msword_backend import MsWordDocumentBackend
      return MsWordDocumentBackend, InputFormat.DOCX
    if media_type == PPTX_MEDIA_TYPE:
      from docling.backend.mspowerpoint_backend import MsPowerpointDocumentBackend
      return MsPowerpointDocumentBackend, InputFormat.PPTX
    if media_type == XLSX_MEDIA_TYPE:
      from docling.backend.msexcel_backend import MsExcelDocumentBackend
      return MsExcelDocumentBackend, InputFormat.XLSX
    if media_type == HTML_MEDIA_TYPE:
      from docling.backend.html_backend import HTMLDocumentBackend
      return HTMLDocumentBackend, InputFormat.HTML
    if media_type in MARKDOWN_MEDIA_TYPES:
      from docling.backend.md_backend import MarkdownDocumentBackend
      return MarkdownDocumentBackend, InputFormat.MD
    raise ValueError("document media type is not supported")

  def _convert(
    self,
    source: Path,
    filename: str,
    backend_type: type[Any],
    input_format: Any,
  ) -> Any:
    from docling.datamodel.document import InputDocument
    from docling.datamodel.settings import DocumentLimits

    input_document = InputDocument(
      source,
      input_format,
      backend_type,
      filename=filename,
      limits=DocumentLimits(  # type: ignore[call-arg]
        max_num_containers=self.limits.containers,
        max_file_size=self.limits.source_bytes,
      ),
    )
    backend = backend_type(input_document, source)
    try:
      if not backend.is_valid():
        raise ValueError("document is invalid")
      return backend.convert()
    finally:
      backend.unload()
