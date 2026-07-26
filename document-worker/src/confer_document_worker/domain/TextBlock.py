from dataclasses import dataclass

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.TextSource import TextSource


@dataclass(frozen=True)
class TextBlock:
  text: str
  bounds: Bounds
  source: TextSource = TextSource.NATIVE
  confidence: float = 1.0

  def __post_init__(self) -> None:
    if not 0 <= self.confidence <= 1:
      raise ValueError("block confidence must be between zero and one")
