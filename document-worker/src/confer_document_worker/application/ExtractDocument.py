from contextlib import ExitStack
from tempfile import NamedTemporaryFile
from typing import BinaryIO, cast

from confer_document_worker.application.requests.ExtractDocumentRequest import ExtractDocumentRequest
from confer_document_worker.application.responses.ExtractDocumentResult import ExtractDocumentResult
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.extraction.DocumentExtractorRouter import DocumentExtractorRouter


class ExtractDocument:
  def __init__(
    self,
    extractor: DocumentExtractorRouter,
    artifact_writer: ArtifactWriter,
  ):
    self.extractor = extractor
    self.artifact_writer = artifact_writer

  def execute(self, request: ExtractDocumentRequest) -> ExtractDocumentResult:
    content = self.extractor.extract(
      request.source,
      request.filename,
      request.media_type,
    )
    with ExitStack() as resources:
      artifact_file = resources.enter_context(
        NamedTemporaryFile(prefix="confer-artifact-", suffix=".bin"),
      )
      artifact = self.artifact_writer.write(
        content,
        cast(BinaryIO, artifact_file),
      )
      result = ExtractDocumentResult(
        text=artifact.text,
        artifact=cast(BinaryIO, artifact_file),
        artifact_length=artifact.length,
      )
      resources.pop_all()
      return result
