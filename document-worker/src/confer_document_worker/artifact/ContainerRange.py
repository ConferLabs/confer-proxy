from dataclasses import dataclass


@dataclass(frozen=True)
class ContainerRange:
  container_number: int
  start: int
  end: int
