from collections.abc import Callable
from pathlib import Path

import pymupdf

from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.domain.RasterizationPolicy import RasterizationPolicy
from confer_document_worker.extraction.pdf.PdfLimits import MAX_PDF_BYTES
from confer_document_worker.rendering.RenderedPage import RenderedPage


class PageRenderer:
  MIN_DPI = 72
  MAX_DPI = 300
  MAX_PNG_BYTES = 16 * 1024 * 1024

  def __init__(
    self,
    reader_factory: Callable[[bytes | Path], ArtifactReader],
    rasterization: RasterizationPolicy | None = None,
  ):
    self.reader_factory = reader_factory
    self.rasterization = rasterization or RasterizationPolicy()

  def render(
    self,
    source: DocumentSource,
    artifact: bytes | Path,
    container_number: int,
    dpi: int = 160,
  ) -> RenderedPage:
    with self.reader_factory(artifact) as reader:
      return self.render_from_reader(source, reader, container_number, dpi)

  def render_from_reader(
    self,
    source: DocumentSource,
    reader: ArtifactReader,
    container_number: int,
    dpi: int = 160,
  ) -> RenderedPage:
    if source.length == 0 or source.length > MAX_PDF_BYTES:
      raise ValueError("source document size is invalid")
    if container_number < 0:
      raise ValueError("container number cannot be negative")
    if dpi < self.MIN_DPI or dpi > self.MAX_DPI:
      raise ValueError("render DPI is outside the allowed range")

    metadata = reader.metadata()
    if source.sha256 != metadata.source_sha256:
      raise ValueError("source document does not match the document artifact")
    if metadata.media_type != "application/pdf":
      raise ValueError("source format does not have a page renderer")

    try:
      document = pymupdf.open(filename=str(source.path), filetype="pdf")
    except pymupdf.FileDataError as error:
      raise ValueError("PDF is invalid") from error
    with document:
      if container_number >= document.page_count:
        raise ValueError("document page is invalid")
      page = document[container_number]
      scale = self.rasterization.bounded_scale(page.rect.width, page.rect.height, dpi)
      pixmap = page.get_pixmap(
        matrix=pymupdf.Matrix(scale, scale),
        colorspace=pymupdf.csRGB,
        alpha=False,
      )
      self.rasterization.validate(pixmap.width, pixmap.height)
      png = pixmap.tobytes("png")
      if len(png) > self.MAX_PNG_BYTES:
        raise ValueError("rendered page exceeds byte limit")
      return RenderedPage(png, pixmap.width, pixmap.height, container_number)
