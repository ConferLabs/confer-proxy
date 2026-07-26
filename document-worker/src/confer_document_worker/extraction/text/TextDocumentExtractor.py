import csv
import io
import re
from pathlib import Path

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.ContainerKind import ContainerKind
from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion
from confer_document_worker.domain.VisualRegionType import VisualRegionType


class TextDocumentExtractor:
  def __init__(
    self,
    max_text_bytes: int = 50 * 1024 * 1024,
    max_output_bytes: int = 256 * 1024 * 1024,
    block_characters: int = 4_000,
  ):
    self.max_text_bytes = max_text_bytes
    self.max_output_bytes = max_output_bytes
    self.block_characters = block_characters

  def extract(self, source: DocumentSource, filename: str, media_type: str) -> DocumentContent:
    if source.length == 0:
      raise ValueError("document is empty")
    if source.length > self.max_text_bytes:
      raise ValueError("document exceeds maximum size")
    content = source.path.read_bytes()
    if len(content) != source.length:
      raise ValueError("document source length changed")
    text = self._decode(content)
    if media_type == "text/csv":
      text = self._csv_markdown(text)
    if len(text.encode("utf-8")) > self.max_output_bytes:
      raise ValueError("extracted text exceeds maximum size")
    blocks = tuple(
      TextBlock(block, Bounds(0, index, 1, index + 1))
      for index, block in enumerate(self._blocks(text))
    )
    height = max(1, len(blocks))
    regions: tuple[VisualRegion, ...] = ()
    if media_type == "text/csv" and blocks:
      regions = (
        VisualRegion(
          "c1-table-1",
          VisualRegionType.TABLE,
          Bounds(0, 0, 1, height),
          renderable=False,
        ),
      )
    return DocumentContent(
      source_sha256=source.sha256,
      containers=(DocumentContainer(0, 1, height, blocks, regions, ContainerKind.TEXT, filename),),
      media_type=media_type,
      title=Path(filename).stem or None,
    )

  def _decode(self, source: bytes) -> str:
    text = source.decode("utf-8-sig", errors="replace")
    replacement_ratio = text.count("\ufffd") / max(1, len(text))
    if replacement_ratio > 0.02:
      raise ValueError("document is not valid UTF-8 text")
    return text.replace("\x00", "")

  def _blocks(self, text: str) -> tuple[str, ...]:
    paragraphs = [value.strip() for value in re.split(r"\n\s*\n", text) if value.strip()]
    blocks: list[str] = []
    for paragraph in paragraphs:
      remaining = paragraph
      while len(remaining) > self.block_characters:
        split = remaining.rfind("\n", 0, self.block_characters)
        if split < self.block_characters // 2:
          split = remaining.rfind(" ", 0, self.block_characters)
        if split < self.block_characters // 2:
          split = self.block_characters
        blocks.append(remaining[:split].strip())
        remaining = remaining[split:].strip()
      if remaining:
        blocks.append(remaining)
    return tuple(blocks)

  def _csv_markdown(self, text: str) -> str:
    rows = csv.reader(io.StringIO(text))
    output: list[str] = []
    for row_number, row in enumerate(rows):
      if row_number >= 1_000_000:
        raise ValueError("CSV contains too many rows")
      if len(row) > 16_384:
        raise ValueError("CSV contains too many columns")
      values = [value.replace("|", "\\|").replace("\n", " ") for value in row]
      output.append("| " + " | ".join(values) + " |")
      if row_number == 0:
        output.append("| " + " | ".join("---" for _ in range(max(1, len(row)))) + " |")
    if not output:
      return ""
    return "\n".join(output)
