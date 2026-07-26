from dataclasses import dataclass


@dataclass(frozen=True)
class OutlineEntry:
  level: int
  title: str
  container_number: int

  def __post_init__(self) -> None:
    if self.level < 1:
      raise ValueError("outline level must be positive")
    if not self.title:
      raise ValueError("outline title is required")
    if self.container_number < 0:
      raise ValueError("outline container cannot be negative")
