from dataclasses import dataclass

from confer_document_worker.domain.ContainerReference import ContainerReference
from confer_document_worker.domain.RegionReference import RegionReference


@dataclass(frozen=True)
class TextWindow:
  container_start: int
  container_end: int
  total_containers: int
  text: str
  regions: tuple[RegionReference, ...]
  containers: tuple[ContainerReference, ...]
