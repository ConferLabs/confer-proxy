import pymupdf
import pytest

from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.rendering.PageRenderer import PageRenderer
from SourceFixture import SourceFixture
from test_extractor import extract_pdf, native_pdf, pdf_extractor


def test_renders_artifact_page_as_bounded_png() -> None:
  pdf = native_pdf()
  artifact = ArtifactWriter().build(extract_pdf(pdf_extractor(scan_workers=1), pdf))

  with SourceFixture(pdf, suffix=".pdf") as source:
    renderer = PageRenderer(ArtifactReader)
    rendered = renderer.render(source, artifact, 0, dpi=160)

  assert rendered.png.startswith(b"\x89PNG\r\n\x1a\n")
  assert rendered.container_number == 0
  assert rendered.width * rendered.height <= renderer.rasterization.max_pixels
  pixmap = pymupdf.Pixmap(rendered.png)
  assert pixmap.width == rendered.width
  assert pixmap.height == rendered.height


def test_rejects_pdf_that_does_not_match_artifact() -> None:
  pdf = native_pdf()
  artifact = ArtifactWriter().build(extract_pdf(pdf_extractor(scan_workers=1), pdf))

  with (
    SourceFixture(pdf + b"trailing change", suffix=".pdf") as source,
    pytest.raises(ValueError, match="does not match"),
  ):
    PageRenderer(ArtifactReader).render(source, artifact, 0)


def test_rejects_page_outside_the_source_document() -> None:
  pdf = native_pdf()
  artifact = ArtifactWriter().build(extract_pdf(pdf_extractor(scan_workers=1), pdf))

  with (
    SourceFixture(pdf, suffix=".pdf") as source,
    pytest.raises(ValueError, match="page is invalid"),
  ):
    PageRenderer(ArtifactReader).render(source, artifact, 1)
