from dataclasses import dataclass
from typing import BinaryIO

from confer_document_worker.application.responses.WorkerResponse import WorkerResponse


@dataclass(frozen=True)
class ExtractDocumentResult(WorkerResponse):
  text: bytes
  artifact: BinaryIO
  artifact_length: int

  def close(self) -> None:
    self.artifact.close()
