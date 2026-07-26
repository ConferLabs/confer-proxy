from dataclasses import dataclass

from confer_document_worker.domain.ContainerKind import ContainerKind


@dataclass(frozen=True)
class ContainerReference:
  number: int
  kind: ContainerKind
  label: str | None
