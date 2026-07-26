import json
from contextlib import ExitStack
from typing import BinaryIO

from confer_document_worker.protocol.ProtocolLimits import (
  HEADER_LENGTH,
  MAX_HEADER_BYTES,
  MAX_PAYLOAD_BYTES,
  MAX_TOTAL_PAYLOAD_BYTES,
)
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload


class ProtocolCodec:
  def read(self, stream: BinaryIO) -> ProtocolMessage:
    encoded_length = self._read_exact(stream, HEADER_LENGTH.size)
    header_length = HEADER_LENGTH.unpack(encoded_length)[0]
    if header_length < 2 or header_length > MAX_HEADER_BYTES:
      raise ValueError("protocol header length is invalid")
    encoded_header = self._read_exact(stream, header_length)
    try:
      header = json.loads(encoded_header)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
      raise ValueError("protocol header is invalid") from error
    if not isinstance(header, dict):
      raise ValueError("protocol header must be an object")
    descriptors = header.pop("payloads", [])
    if not isinstance(descriptors, list):
      raise ValueError("protocol payload descriptors are invalid")

    with ExitStack() as resources:
      payloads: dict[PayloadRole, ProtocolPayload] = {}
      total = 0
      for descriptor in descriptors:
        if not isinstance(descriptor, dict):
          raise ValueError("protocol payload descriptor is invalid")
        role_value = descriptor.get("role")
        media_type = descriptor.get("media_type")
        length = descriptor.get("length")
        try:
          role = PayloadRole(role_value) if isinstance(role_value, str) else None
        except ValueError:
          role = None
        if role is None or role in payloads:
          raise ValueError("protocol payload role is invalid")
        if (
          not isinstance(media_type, str)
          or not role.accepts_media_type(media_type)
        ):
          raise ValueError("protocol payload media type is invalid")
        if not isinstance(length, int) or isinstance(length, bool) or length < 0 or length > MAX_PAYLOAD_BYTES:
          raise ValueError("protocol payload length is invalid")
        total += length
        if total > MAX_TOTAL_PAYLOAD_BYTES:
          raise ValueError("protocol payloads exceed total limit")
        payload = ProtocolPayload.receive(stream, length, media_type)
        resources.callback(payload.close)
        payloads[role] = payload
      message = ProtocolMessage(header, payloads)
      resources.pop_all()
      return message

  def write(self, stream: BinaryIO, message: ProtocolMessage) -> None:
    header = dict(message.header)
    descriptors: list[dict[str, object]] = []
    total = 0
    for role, payload in message.payloads.items():
      if not isinstance(role, PayloadRole):
        raise ValueError("protocol payload role is invalid")
      if (
        not role.accepts_media_type(payload.media_type)
      ):
        raise ValueError("protocol payload media type is invalid")
      if payload.length > MAX_PAYLOAD_BYTES:
        raise ValueError("protocol payload length is invalid")
      total += payload.length
      if total > MAX_TOTAL_PAYLOAD_BYTES:
        raise ValueError("protocol payloads exceed total limit")
      descriptors.append({
        "role": role.value,
        "media_type": payload.media_type,
        "length": payload.length,
      })
    header["payloads"] = descriptors
    encoded = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_HEADER_BYTES:
      raise ValueError("protocol header exceeds maximum size")
    stream.write(HEADER_LENGTH.pack(len(encoded)))
    stream.write(encoded)
    for payload in message.payloads.values():
      payload.write_to(stream)
    stream.flush()

  def _read_exact(self, stream: BinaryIO, length: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < length:
      chunk = stream.read(length - len(chunks))
      if not chunk:
        raise ValueError("protocol message is truncated")
      chunks.extend(chunk)
    return bytes(chunks)
