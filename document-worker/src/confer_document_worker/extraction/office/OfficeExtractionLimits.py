from dataclasses import dataclass


@dataclass(frozen=True)
class OfficeExtractionLimits:
  source_bytes: int = 50 * 1024 * 1024
  containers: int = 5_000
  image_pixels: int = 8_000_000
  image_bytes: int = 8 * 1024 * 1024
  total_image_bytes: int = 32 * 1024 * 1024
