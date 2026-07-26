from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.extraction.MediaTypes import STRUCTURED_MEDIA_TYPES, SUPPORTED_MEDIA_TYPES
from confer_document_worker.extraction.office.DoclingSlimDocumentExtractor import DoclingSlimDocumentExtractor
from confer_document_worker.extraction.pdf.PdfExtractor import PdfExtractor
from confer_document_worker.extraction.text.TextDocumentExtractor import TextDocumentExtractor


class DocumentExtractorRouter:
  def __init__(
    self,
    pdf_extractor: PdfExtractor,
    structured_extractor: DoclingSlimDocumentExtractor,
    text_extractor: TextDocumentExtractor,
  ):
    self.pdf_extractor = pdf_extractor
    self.structured_extractor = structured_extractor
    self.text_extractor = text_extractor

  def extract(self, source: DocumentSource, filename: str, media_type: str) -> DocumentContent:
    normalized = media_type.split(";", 1)[0].strip().casefold()
    if normalized not in SUPPORTED_MEDIA_TYPES:
      raise ValueError("document media type is not supported")
    if normalized == "application/pdf":
      return self.pdf_extractor.extract(source)
    if normalized in STRUCTURED_MEDIA_TYPES:
      return self.structured_extractor.extract(source, filename, normalized)
    return self.text_extractor.extract(source, filename, normalized)
