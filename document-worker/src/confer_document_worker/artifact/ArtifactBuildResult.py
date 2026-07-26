from dataclasses import dataclass


@dataclass(frozen=True)
class ArtifactBuildResult:
  length: int
  text: bytes
