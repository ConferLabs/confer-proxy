from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class Bounds:
  x0: float
  y0: float
  x1: float
  y1: float

  def __post_init__(self) -> None:
    values = (self.x0, self.y0, self.x1, self.y1)
    if not all(math.isfinite(value) for value in values):
      raise ValueError("bounds must be finite")
    if self.x0 < 0 or self.y0 < 0 or self.x1 < self.x0 or self.y1 < self.y0:
      raise ValueError("bounds must form a non-negative rectangle")

  def intersects(self, other: Bounds) -> bool:
    return self.x0 < other.x1 and self.x1 > other.x0 and self.y0 < other.y1 and self.y1 > other.y0
