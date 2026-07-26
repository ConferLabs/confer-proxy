from enum import StrEnum


class ContainerKind(StrEnum):
  PAGE = "page"
  SLIDE = "slide"
  SHEET = "sheet"
  DOCUMENT = "document"
  TEXT = "text"
