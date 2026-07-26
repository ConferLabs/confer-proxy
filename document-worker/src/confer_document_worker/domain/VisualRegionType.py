from enum import StrEnum


class VisualRegionType(StrEnum):
  IMAGE = "image"
  TABLE = "table"
  FORM = "form"
  CHART = "chart"
  SCANNED_PAGE = "scanned_page"
  LOW_CONFIDENCE = "low_confidence"
