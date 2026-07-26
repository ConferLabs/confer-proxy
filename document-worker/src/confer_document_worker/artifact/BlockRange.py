from dataclasses import dataclass


@dataclass(frozen=True)
class BlockRange:
  start: int
  end: int
