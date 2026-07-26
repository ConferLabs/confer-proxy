from __future__ import annotations

from collections.abc import Mapping

from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload


class ProtocolMessage:
  def __init__(
    self,
    header: Mapping[str, object],
    payloads: Mapping[PayloadRole, bytes | ProtocolPayload],
  ):
    self.header = dict(header)
    self.payloads: dict[PayloadRole, ProtocolPayload] = {}
    for role, value in payloads.items():
      if not isinstance(role, PayloadRole):
        raise ValueError("protocol payload role is invalid")
      payload = value if isinstance(value, ProtocolPayload) else ProtocolPayload.from_bytes(
        value,
        role.media_type,
      )
      if not role.accepts_media_type(payload.media_type):
        raise ValueError("protocol payload media type does not match its role")
      self.payloads[role] = payload

  def close(self) -> None:
    for payload in self.payloads.values():
      payload.close()

  def __enter__(self) -> ProtocolMessage:
    return self

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()
