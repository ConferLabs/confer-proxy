from enum import StrEnum


class PayloadRole(StrEnum):
  SOURCE = "source"
  ARTIFACT = "artifact"
  TEXT = "text"
  RESULT = "result"
  IMAGE = "image"

  @property
  def media_type(self) -> str:
    if self is PayloadRole.ARTIFACT:
      return "application/vnd.confer.document-artifact"
    if self is PayloadRole.TEXT:
      return "text/plain; charset=utf-8"
    if self is PayloadRole.RESULT:
      return "application/json"
    if self is PayloadRole.IMAGE:
      return "image/png"
    raise ValueError("source payloads require an explicit media type")

  def accepts_media_type(self, value: str) -> bool:
    return bool(value and not value.isspace()) and (
      self is PayloadRole.SOURCE or value == self.media_type
    )
