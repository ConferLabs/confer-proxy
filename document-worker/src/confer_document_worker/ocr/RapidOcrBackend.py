from collections.abc import Callable
from typing import Any

import numpy

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.TextSource import TextSource
from confer_document_worker.extraction.pdf.RasterImage import RasterImage


class RapidOcrBackend:
  def __init__(self, engine: Callable[[numpy.ndarray], Any], max_concurrency: int = 1):
    self.engine = engine
    self.max_concurrency = max_concurrency

  def extract(self, image: RasterImage) -> tuple[TextBlock, ...]:
    pixels = numpy.frombuffer(image.samples, dtype=numpy.uint8).reshape(
      (image.height, image.width, 3),
    )
    result = self.engine(pixels)
    if result.boxes is None or result.txts is None or result.scores is None:
      return ()

    x_scale = image.page_width / image.width
    y_scale = image.page_height / image.height
    blocks: list[TextBlock] = []
    for box, text, score in zip(result.boxes, result.txts, result.scores, strict=True):
      if not text.strip():
        continue
      x_values = [float(point[0]) * x_scale for point in box]
      y_values = [float(point[1]) * y_scale for point in box]
      bounds = Bounds(
        round(image.page_x0 + max(0.0, min(x_values)), 2),
        round(image.page_y0 + max(0.0, min(y_values)), 2),
        round(image.page_x0 + min(image.page_width, max(x_values)), 2),
        round(image.page_y0 + min(image.page_height, max(y_values)), 2),
      )
      blocks.append(TextBlock(text.strip() + "\n", bounds, TextSource.OCR, float(score)))
    return tuple(blocks)
