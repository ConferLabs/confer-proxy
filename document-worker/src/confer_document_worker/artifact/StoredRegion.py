from dataclasses import dataclass

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.VisualRegionType import VisualRegionType


@dataclass(frozen=True)
class StoredRegion:
  id: str
  container_number: int
  type: VisualRegionType
  bounds: Bounds
  confidence: float
  renderable: bool
  asset_media_type: str | None
  asset: bytes | None
