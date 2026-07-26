from __future__ import annotations

import pickle

import pymupdf

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.RasterizationPolicy import RasterizationPolicy
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.TextSource import TextSource
from confer_document_worker.domain.VisualRegionType import VisualRegionType
from confer_document_worker.extraction.pdf.OcrBackend import OcrBackend
from confer_document_worker.extraction.pdf.OcrRequiredError import OcrRequiredError
from confer_document_worker.extraction.pdf.PageClassifier import PageClassifier
from confer_document_worker.extraction.pdf.PdfExtractor import PdfExtractor
from confer_document_worker.extraction.pdf.PdfOcrProcessor import PdfOcrProcessor
from confer_document_worker.extraction.pdf.PdfPageRangeScanner import PdfPageRangeScanner
from confer_document_worker.extraction.pdf.PdfPageScanner import PdfPageScanner
from confer_document_worker.extraction.pdf.RasterImage import RasterImage
from confer_document_worker.extraction.pdf.TableDetector import TableDetector
from SourceFixture import SourceFixture


class FakeOcrBackend:
  max_concurrency = 1

  def __init__(self):
    self.images: list[RasterImage] = []

  def extract(self, image: RasterImage) -> tuple[TextBlock, ...]:
    assert image.width > 0
    assert image.height > 0
    self.images.append(image)
    return (
      TextBlock(
        "OCR recovered analyte 500 ng/dL",
        Bounds(20, 20, image.page_width - 20, 60),
        TextSource.OCR,
        0.72,
      ),
    )


def pdf_extractor(
  ocr_backend: OcrBackend | None = None,
  scan_workers: int | None = None,
  rasterization: RasterizationPolicy | None = None,
) -> PdfExtractor:
  page_scanner = PdfPageScanner(PageClassifier(), TableDetector())
  return PdfExtractor(
    page_scanner,
    PdfPageRangeScanner(page_scanner),
    PdfOcrProcessor(ocr_backend, rasterization=rasterization),
    scan_workers=scan_workers,
  )


def native_pdf() -> bytes:
  document = pymupdf.open()
  document.set_metadata({"title": "  Example   report  "})
  page = document.new_page(width=612, height=792)
  page.insert_text((40, 60), "Patient laboratory report")
  rows = [
    ("Test", "Result", "Reference"),
    ("Analyte", "500 ng/dL", "300-1000"),
    ("Vitamin D", "32 ng/mL", "30-100"),
    ("Ferritin", "80 ng/mL", "20-300"),
  ]
  for row_index, row in enumerate(rows):
    y = 120 + row_index * 30
    for column_index, text in enumerate(row):
      page.insert_text((50 + column_index * 170, y), text)
  for x in (40, 210, 380, 550):
    page.draw_line((x, 95), (x, 225))
  for y in (95, 125, 155, 185, 225):
    page.draw_line((40, y), (550, y))
  data = document.tobytes()
  document.close()
  return data


def scanned_pdf() -> bytes:
  source = pymupdf.open()
  source_page = source.new_page(width=300, height=400)
  source_page.insert_text((30, 60), "Scanned laboratory result")
  pixmap = source_page.get_pixmap(matrix=pymupdf.Matrix(2, 2), alpha=False)

  scanned = pymupdf.open()
  page = scanned.new_page(width=300, height=400)
  page.insert_image(page.rect, pixmap=pixmap)
  data = scanned.tobytes()
  scanned.close()
  source.close()
  return data


def oversized_scanned_pdf() -> bytes:
  pixmap = pymupdf.Pixmap(
    pymupdf.csRGB,
    pymupdf.IRect(0, 0, 400, 400),
    False,
  )
  pixmap.clear_with(255)

  scanned = pymupdf.open()
  page = scanned.new_page(width=300, height=400)
  page.insert_image(page.rect, pixmap=pixmap)
  data = scanned.tobytes()
  scanned.close()
  return data


def extract_pdf(extractor: PdfExtractor, content: bytes):
  with SourceFixture(content, suffix=".pdf") as source:
    return extractor.extract(source)


def test_extracts_native_blocks_and_table_regions() -> None:
  content = extract_pdf(pdf_extractor(), native_pdf())

  assert content.title == "Example report"
  assert "Analyte" in "".join(block.text for block in content.containers[0].blocks)
  assert all(block.source == TextSource.NATIVE for block in content.containers[0].blocks)
  assert any(region.type == VisualRegionType.TABLE for region in content.containers[0].regions)


def test_routes_full_page_image_to_ocr() -> None:
  content = extract_pdf(pdf_extractor(FakeOcrBackend()), scanned_pdf())

  assert content.containers[0].blocks[0].source == TextSource.OCR
  assert any(region.type == VisualRegionType.SCANNED_PAGE for region in content.containers[0].regions)
  assert any(region.type == VisualRegionType.LOW_CONFIDENCE for region in content.containers[0].regions)


def test_bounds_ocr_rasterization_before_decoding_embedded_image() -> None:
  backend = FakeOcrBackend()
  rasterization = RasterizationPolicy(max_pixels=100_000)

  extract_pdf(
    pdf_extractor(backend, rasterization=rasterization),
    oversized_scanned_pdf(),
  )

  assert len(backend.images) == 1
  assert backend.images[0].width * backend.images[0].height <= rasterization.max_pixels


def test_fails_instead_of_silently_skipping_required_ocr() -> None:
  try:
    extract_pdf(pdf_extractor(), scanned_pdf())
  except OcrRequiredError as error:
    assert "page 1" in str(error)
  else:
    raise AssertionError("expected OCR-required failure")


def test_page_range_scanner_remains_serializable_for_process_workers() -> None:
  scanner = PdfPageRangeScanner(PdfPageScanner(PageClassifier(), TableDetector()))
  restored = pickle.loads(pickle.dumps(scanner))

  with SourceFixture(native_pdf(), suffix=".pdf") as source:
    drafts = restored((str(source.path), 0, 1))

  assert len(drafts) == 1
  assert drafts[0].number == 0
  assert "Analyte" in "".join(block.text for block in drafts[0].blocks)


def test_rejects_invalid_pdf() -> None:
  try:
    extract_pdf(pdf_extractor(), b"not a PDF")
  except ValueError as error:
    assert "invalid" in str(error)
  else:
    raise AssertionError("expected invalid-PDF failure")
