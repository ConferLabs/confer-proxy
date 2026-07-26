from dataclasses import dataclass


@dataclass(frozen=True)
class ArtifactMetadata:
  schema_version: int
  source_sha256: str
  media_type: str
  title: str | None
  text_bytes: int
  container_count: int
  outline_count: int
  page_count: int | None

  def __post_init__(self) -> None:
    if self.schema_version < 1:
      raise ValueError("artifact schema version must be positive")
    if len(self.source_sha256) != 64 or any(
      character not in "0123456789abcdefABCDEF" for character in self.source_sha256
    ):
      raise ValueError("artifact source hash is invalid")
    if not self.media_type:
      raise ValueError("artifact media type is required")
    if min(self.text_bytes, self.container_count, self.outline_count) < 0:
      raise ValueError("artifact metadata counts cannot be negative")
    if self.page_count is not None and self.page_count < 0:
      raise ValueError("artifact page count cannot be negative")
