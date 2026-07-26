from __future__ import annotations

import hashlib
from contextlib import ExitStack
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import BinaryIO, cast


class ProtocolPayload:
  COPY_BUFFER_BYTES = 64 * 1024

  def __init__(
    self,
    media_type: str,
    length: int,
    content: bytes | None = None,
    file: BinaryIO | None = None,
  ):
    if not media_type or media_type.isspace():
      raise ValueError("protocol payload media type is invalid")
    if length < 0:
      raise ValueError("protocol payload length cannot be negative")
    if (content is None) == (file is None):
      raise ValueError("protocol payload must have exactly one backing store")
    if content is not None and len(content) != length:
      raise ValueError("protocol payload content has an invalid length")
    self.media_type = media_type
    self.length = length
    self._sha256: str | None = None
    self._content = content
    self._file = file

  @classmethod
  def from_bytes(cls, content: bytes, media_type: str) -> ProtocolPayload:
    return cls(
      media_type,
      len(content),
      content=content,
    )

  @classmethod
  def from_file(
    cls,
    file: BinaryIO,
    length: int,
    media_type: str,
  ) -> ProtocolPayload:
    file.flush()
    file.seek(0, 2)
    if file.tell() != length:
      raise ValueError("protocol payload backing file has an invalid length")
    file.seek(0)
    return cls(media_type, length, file=file)

  @classmethod
  def receive(cls, stream: BinaryIO, length: int, media_type: str) -> ProtocolPayload:
    if length < 0:
      raise ValueError("protocol payload length cannot be negative")
    with ExitStack() as resources:
      temporary = resources.enter_context(
        NamedTemporaryFile(prefix="confer-payload-", suffix=".bin"),
      )
      remaining = length
      while remaining:
        chunk = stream.read(min(cls.COPY_BUFFER_BYTES, remaining))
        if not chunk:
          raise ValueError("protocol message is truncated")
        if len(chunk) > remaining:
          raise ValueError("protocol payload stream returned too many bytes")
        temporary.write(chunk)
        remaining -= len(chunk)
      temporary.flush()
      temporary.seek(0)
      payload = cls(
        media_type,
        length,
        file=cast(BinaryIO, temporary),
      )
      resources.pop_all()
      return payload

  @property
  def path(self) -> Path:
    if self._file is None:
      raise ValueError("protocol payload is not file-backed")
    return Path(self._file.name)

  @property
  def sha256(self) -> str:
    if self._sha256 is None:
      digest = hashlib.sha256()
      if self._content is not None:
        digest.update(self._content)
      else:
        file = self._required_file()
        file.seek(0)
        while chunk := file.read(self.COPY_BUFFER_BYTES):
          digest.update(chunk)
        file.seek(0)
      self._sha256 = digest.hexdigest()
    return self._sha256

  def read_bytes(self, maximum: int | None = None) -> bytes:
    if maximum is not None and self.length > maximum:
      raise ValueError("protocol payload exceeds maximum size")
    if self._content is not None:
      return self._content
    file = self._required_file()
    file.seek(0)
    content = file.read(self.length + 1)
    if len(content) != self.length:
      raise ValueError("protocol payload backing file has an invalid length")
    return content

  def write_to(self, stream: BinaryIO) -> None:
    if self._content is not None:
      stream.write(self._content)
      return
    file = self._required_file()
    file.seek(0)
    remaining = self.length
    while remaining:
      chunk = file.read(min(self.COPY_BUFFER_BYTES, remaining))
      if not chunk:
        raise ValueError("protocol payload backing file is truncated")
      stream.write(chunk)
      remaining -= len(chunk)

  def close(self) -> None:
    if self._file is not None:
      self._file.close()
      self._file = None

  def _required_file(self) -> BinaryIO:
    if self._file is None:
      raise RuntimeError("protocol payload backing file is closed")
    return self._file

  def __enter__(self) -> ProtocolPayload:
    return self

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()
