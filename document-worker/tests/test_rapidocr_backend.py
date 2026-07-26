from __future__ import annotations

from dataclasses import dataclass

import numpy
import pytest

pytest.importorskip("rapidocr")

from confer_document_worker.extraction.pdf.RasterImage import RasterImage
from confer_document_worker.ocr.RapidOcrBackend import RapidOcrBackend


@dataclass
class FakeResult:
  boxes: numpy.ndarray
  txts: tuple[str, ...]
  scores: tuple[float, ...]


class FakeEngine:
  def __call__(self, pixels: numpy.ndarray) -> FakeResult:
    assert pixels.shape == (200, 100, 3)
    return FakeResult(
      boxes=numpy.array((
        ((10, 20), (90, 20), (90, 40), (10, 40)),
      )),
      txts=("Analyte 500 ng/dL",),
      scores=(0.91,),
    )


def test_maps_raster_coordinates_back_to_pdf_page() -> None:
  image = RasterImage(
    width=100,
    height=200,
    samples=bytes(100 * 200 * 3),
    page_width=200,
    page_height=400,
  )

  blocks = RapidOcrBackend(FakeEngine()).extract(image)

  assert len(blocks) == 1
  assert blocks[0].text == "Analyte 500 ng/dL\n"
  assert blocks[0].bounds.x0 == 20
  assert blocks[0].bounds.y0 == 40
  assert blocks[0].bounds.x1 == 180
  assert blocks[0].bounds.y1 == 80
  assert blocks[0].confidence == 0.91
