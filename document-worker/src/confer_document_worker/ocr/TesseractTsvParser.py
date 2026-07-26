import csv
import io
from collections import defaultdict

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.TextSource import TextSource
from confer_document_worker.extraction.pdf.RasterImage import RasterImage


class TesseractTsvParser:
  def parse(self, tsv: str, image: RasterImage) -> tuple[TextBlock, ...]:
    lines: dict[tuple[str, str, str], list[dict[str, str]]] = defaultdict(list)
    for row in csv.DictReader(io.StringIO(tsv), delimiter="\t"):
      if row.get("level") != "5" or not row.get("text", "").strip():
        continue
      lines[(row["block_num"], row["par_num"], row["line_num"])].append(row)

    x_scale = image.page_width / image.width
    y_scale = image.page_height / image.height
    blocks: list[TextBlock] = []
    for words in lines.values():
      left = min(int(word["left"]) for word in words)
      top = min(int(word["top"]) for word in words)
      right = max(int(word["left"]) + int(word["width"]) for word in words)
      bottom = max(int(word["top"]) + int(word["height"]) for word in words)
      confidences = [max(0.0, float(word["conf"])) / 100 for word in words]
      blocks.append(TextBlock(
        " ".join(word["text"].strip() for word in words) + "\n",
        Bounds(
          round(image.page_x0 + left * x_scale, 2),
          round(image.page_y0 + top * y_scale, 2),
          round(image.page_x0 + right * x_scale, 2),
          round(image.page_y0 + bottom * y_scale, 2),
        ),
        TextSource.OCR,
        sum(confidences) / len(confidences),
      ))
    return tuple(blocks)
