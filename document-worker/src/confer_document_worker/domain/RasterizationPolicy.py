import math


class RasterizationPolicy:
  DEFAULT_MAX_PIXELS = 8_000_000

  def __init__(self, max_pixels: int = DEFAULT_MAX_PIXELS):
    if max_pixels < 1:
      raise ValueError("rasterization pixel limit must be positive")
    self.max_pixels = max_pixels

  def bounded_scale(self, width: float, height: float, dpi: int) -> float:
    if (
      not math.isfinite(width)
      or not math.isfinite(height)
      or width <= 0
      or height <= 0
      or dpi <= 0
    ):
      raise ValueError("rasterization dimensions must be positive and finite")
    scale = dpi / 72
    while True:
      scaled_width = max(1, math.ceil(width * scale))
      scaled_height = max(1, math.ceil(height * scale))
      pixels = scaled_width * scaled_height
      if pixels <= self.max_pixels:
        return scale
      if scaled_width == 1:
        scale = min(scale, self.max_pixels / height) * 0.99
      elif scaled_height == 1:
        scale = min(scale, self.max_pixels / width) * 0.99
      else:
        scale *= math.sqrt(self.max_pixels / pixels) * 0.99

  def permits(self, width: int, height: int) -> bool:
    return width > 0 and height > 0 and width * height <= self.max_pixels

  def validate(self, width: int, height: int) -> None:
    if not self.permits(width, height):
      raise ValueError("rasterized image exceeds pixel limit")
