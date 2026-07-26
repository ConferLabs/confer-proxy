from dataclasses import dataclass


@dataclass(frozen=True)
class RenderedPage:
  png: bytes
  width: int
  height: int
  container_number: int
