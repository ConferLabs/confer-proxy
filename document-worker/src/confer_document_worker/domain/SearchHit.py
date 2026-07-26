from dataclasses import dataclass

from confer_document_worker.domain.ContainerReference import ContainerReference
from confer_document_worker.domain.RegionReference import RegionReference


@dataclass(frozen=True)
class SearchHit:
  chunk_id: int
  ordinal: int
  score: float
  container_start: int
  container_end: int
  text: str
  regions: tuple[RegionReference, ...]
  containers: tuple[ContainerReference, ...]
