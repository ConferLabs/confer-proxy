from dataclasses import dataclass


@dataclass(frozen=True)
class RasterImage:
  width: int
  height: int
  samples: bytes
  page_width: float
  page_height: float
  page_x0: float = 0
  page_y0: float = 0
