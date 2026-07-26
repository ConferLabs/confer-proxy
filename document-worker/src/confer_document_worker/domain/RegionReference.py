from dataclasses import dataclass

from confer_document_worker.domain.VisualRegionType import VisualRegionType


@dataclass(frozen=True)
class RegionReference:
  id: str
  container_number: int
  type: VisualRegionType
  confidence: float
  renderable: bool
