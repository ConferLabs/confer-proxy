from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.extraction.office.DoclingDocumentConverter import DoclingDocumentConverter
from confer_document_worker.extraction.office.DoclingDocumentMapper import DoclingDocumentMapper
from confer_document_worker.extraction.office.OfficeArchivePreflight import OfficeArchivePreflight
from confer_document_worker.extraction.office.OfficeExtractionLimits import OfficeExtractionLimits


class DoclingSlimDocumentExtractor:
  def __init__(
    self,
    archive_preflight: OfficeArchivePreflight,
    converter: DoclingDocumentConverter,
    mapper: DoclingDocumentMapper,
    limits: OfficeExtractionLimits,
  ):
    self.archive_preflight = archive_preflight
    self.converter = converter
    self.mapper = mapper
    self.limits = limits

  def extract(
    self,
    source: DocumentSource,
    filename: str,
    media_type: str,
  ) -> DocumentContent:
    if source.length == 0:
      raise ValueError("document is empty")
    if source.length > self.limits.source_bytes:
      raise ValueError("document exceeds maximum size")
    self.archive_preflight.validate(source.path, media_type)
    document = self.converter.convert(source.path, filename, media_type)
    return self.mapper.map(document, source, filename, media_type)
