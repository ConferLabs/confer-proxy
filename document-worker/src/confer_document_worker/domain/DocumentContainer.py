import math
from dataclasses import dataclass, field

from confer_document_worker.domain.ContainerKind import ContainerKind
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion


@dataclass(frozen=True)
class DocumentContainer:
  number: int
  width: float
  height: float
  blocks: tuple[TextBlock, ...] = field(default_factory=tuple)
  regions: tuple[VisualRegion, ...] = field(default_factory=tuple)
  kind: ContainerKind = ContainerKind.PAGE
  label: str | None = None

  def __post_init__(self) -> None:
    if self.number < 0:
      raise ValueError("container number cannot be negative")
    if not math.isfinite(self.width) or not math.isfinite(self.height):
      raise ValueError("container dimensions must be finite")
    if self.width <= 0 or self.height <= 0:
      raise ValueError("container dimensions must be positive")
