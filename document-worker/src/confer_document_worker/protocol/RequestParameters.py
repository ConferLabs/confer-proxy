from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.protocol.PayloadRole import PayloadRole
from confer_document_worker.protocol.ProtocolMessage import ProtocolMessage
from confer_document_worker.protocol.ProtocolPayload import ProtocolPayload


class RequestParameters:
  def __init__(self, request: ProtocolMessage):
    self.request = request

  def payload(self, role: PayloadRole) -> ProtocolPayload:
    payload = self.request.payloads.get(role)
    if payload is None:
      raise InvalidWorkerRequestError(f"{role.value} payload is required")
    return payload

  def source(self, role: PayloadRole, media_type: str) -> DocumentSource:
    payload = self.payload(role)
    if payload.media_type != media_type:
      raise InvalidWorkerRequestError(
        f"{role.value} payload has the wrong media type"
      )
    return DocumentSource(payload.path, payload.length, payload.sha256)

  def string(self, name: str) -> str:
    value = self.request.header.get(name)
    if not isinstance(value, str) or not value:
      raise InvalidWorkerRequestError(f"{name} must be a non-empty string")
    return value

  def integer(self, name: str, default: int | None = None) -> int:
    value = self.request.header.get(name, default)
    if not isinstance(value, int) or isinstance(value, bool):
      raise InvalidWorkerRequestError(f"{name} must be an integer")
    return value

  def object_array(self, name: str) -> tuple[dict[str, object], ...]:
    value = self.request.header.get(name)
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
      raise InvalidWorkerRequestError(f"{name} must be an array of objects")
    return tuple(value)

  def optional_integer(self, name: str) -> int | None:
    value = self.request.header.get(name)
    if value is None:
      return None
    if not isinstance(value, int) or isinstance(value, bool):
      raise InvalidWorkerRequestError(f"{name} must be an integer")
    return value

  def number(self, name: str, default: float | int | None = None) -> float:
    value = self.request.header.get(name, default)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
      raise InvalidWorkerRequestError(f"{name} must be a number")
    return float(value)
