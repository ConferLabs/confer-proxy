from dataclasses import dataclass

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse
from confer_document_worker.artifact.ArtifactMetadata import ArtifactMetadata
from confer_document_worker.domain.ContainerReference import ContainerReference
from confer_document_worker.domain.OutlineEntry import OutlineEntry


@dataclass(frozen=True)
class DocumentOverviewResult(WorkerResponse):
  metadata: ArtifactMetadata
  outline: tuple[OutlineEntry, ...]
  labeled_containers: tuple[ContainerReference, ...]
  first_chunk_id: int | None
  content: str | None
