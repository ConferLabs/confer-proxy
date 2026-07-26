import math

import pytest

from confer_document_worker.domain.RasterizationPolicy import RasterizationPolicy


@pytest.mark.parametrize(("width", "height", "max_pixels"), (
  (1, 1, 1),
  (10_000, 1, 100),
  (1, 10_000, 100),
  (300, 400, 100_000),
  (100_000, 100_000, 8_000_000),
))
def test_bounded_scale_never_exceeds_pixel_limit(
  width: float,
  height: float,
  max_pixels: int,
) -> None:
  policy = RasterizationPolicy(max_pixels)

  scale = policy.bounded_scale(width, height, dpi=300)
  rasterized_pixels = math.ceil(width * scale) * math.ceil(height * scale)

  assert rasterized_pixels <= max_pixels


@pytest.mark.parametrize(("width", "height", "dpi"), (
  (0, 1, 300),
  (1, 0, 300),
  (math.inf, 1, 300),
  (1, math.nan, 300),
  (1, 1, 0),
))
def test_rejects_invalid_rasterization_dimensions(
  width: float,
  height: float,
  dpi: int,
) -> None:
  with pytest.raises(ValueError, match="positive and finite"):
    RasterizationPolicy().bounded_scale(width, height, dpi)


def test_rejects_invalid_pixel_limit() -> None:
  with pytest.raises(ValueError, match="pixel limit"):
    RasterizationPolicy(0)


def test_rejects_raster_dimensions_over_the_limit() -> None:
  with pytest.raises(ValueError, match="exceeds pixel limit"):
    RasterizationPolicy(100).validate(11, 10)
