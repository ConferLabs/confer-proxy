from dataclasses import dataclass, field

from confer_document_worker.domain.ContainerKind import ContainerKind
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion


@dataclass
class ContainerDraft:
  number: int
  width: float
  height: float
  kind: ContainerKind
  label: str | None = None
  blocks: list[TextBlock] = field(default_factory=list)
  regions: list[VisualRegion] = field(default_factory=list)
