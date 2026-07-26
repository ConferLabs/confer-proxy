from dataclasses import dataclass

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.VisualRegionType import VisualRegionType


@dataclass(frozen=True)
class VisualRegion:
  id: str
  type: VisualRegionType
  bounds: Bounds
  confidence: float = 1.0
  renderable: bool = True
  asset_media_type: str | None = None
  asset: bytes | None = None

  def __post_init__(self) -> None:
    if not self.id:
      raise ValueError("region ID is required")
    if not 0 <= self.confidence <= 1:
      raise ValueError("region confidence must be between zero and one")
    if self.asset is not None and not self.asset:
      raise ValueError("region asset cannot be empty")
    if self.asset is not None and self.asset_media_type != "image/png":
      raise ValueError("region assets must be PNG images")
    if self.asset is None and self.asset_media_type is not None:
      raise ValueError("region media type requires an asset")
    if self.asset is not None and not self.renderable:
      raise ValueError("region assets must be renderable")
