from dataclasses import dataclass, field

from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.OutlineEntry import OutlineEntry


@dataclass(frozen=True)
class DocumentContent:
  source_sha256: str
  containers: tuple[DocumentContainer, ...]
  media_type: str = "application/pdf"
  title: str | None = None
  outline: tuple[OutlineEntry, ...] = field(default_factory=tuple)

  def __post_init__(self) -> None:
    if len(self.source_sha256) != 64:
      raise ValueError("source SHA-256 must contain 64 hexadecimal characters")
    try:
      int(self.source_sha256, 16)
    except ValueError as error:
      raise ValueError("source SHA-256 must be hexadecimal") from error
    if not self.media_type:
      raise ValueError("document media type is required")
    expected = list(range(len(self.containers)))
    actual = [container.number for container in self.containers]
    if actual != expected:
      raise ValueError("document containers must be zero-based and contiguous")
    if any(entry.container_number >= len(self.containers) for entry in self.outline):
      raise ValueError("outline entry references a missing document container")
