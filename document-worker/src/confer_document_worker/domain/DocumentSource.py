from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DocumentSource:
  path: Path
  length: int
  sha256: str

  def __post_init__(self) -> None:
    if self.length < 0:
      raise ValueError("document source length cannot be negative")
    if len(self.sha256) != 64 or any(
      character not in "0123456789abcdefABCDEF" for character in self.sha256
    ):
      raise ValueError("document source hash is invalid")
    object.__setattr__(self, "sha256", self.sha256.lower())
