from collections import deque
import json
from typing import BinaryIO


class WorkerErrorReporter:
  MAX_EXCEPTIONS = 4
  MAX_FRAMES = 32
  PREFIX = b"ERROR document_worker "

  def __init__(self, output: BinaryIO):
    self.output = output

  def report(self, request: object, error: BaseException) -> None:
    exception_chain, chain_truncated = self._exception_chain(error)
    diagnostic = {
      "request": type(request).__qualname__,
      "exception_chain": exception_chain,
      "chain_truncated": chain_truncated,
    }
    message = self.PREFIX + json.dumps(
      diagnostic,
      ensure_ascii=True,
      separators=(",", ":"),
    ).encode("ascii") + b"\n"

    try:
      self.output.write(message)
      self.output.flush()
    except OSError:
      return

  def _exception_chain(
    self,
    error: BaseException,
  ) -> tuple[list[dict[str, object]], bool]:
    exceptions: list[dict[str, object]] = []
    seen: set[int] = set()
    current: BaseException | None = error

    while current is not None and len(exceptions) < self.MAX_EXCEPTIONS:
      identity = id(current)
      if identity in seen:
        return exceptions, True
      seen.add(identity)

      frames, frames_truncated = self._frames(current)
      error_type = type(current)
      exceptions.append({
        "type": f"{error_type.__module__}.{error_type.__qualname__}",
        "frames": frames,
        "frames_truncated": frames_truncated,
      })
      current = self._cause(current)

    return exceptions, current is not None

  def _frames(self, error: BaseException) -> tuple[list[str], bool]:
    frames: deque[str] = deque(maxlen=self.MAX_FRAMES)
    frame_count = 0
    traceback = error.__traceback__

    while traceback is not None:
      frame = traceback.tb_frame
      module = frame.f_globals.get("__name__", "unknown")
      module_name = module if isinstance(module, str) else "unknown"
      frames.append(
        f"{module_name}.{frame.f_code.co_qualname}:{traceback.tb_lineno}",
      )
      frame_count += 1
      traceback = traceback.tb_next

    return list(frames), frame_count > self.MAX_FRAMES

  def _cause(self, error: BaseException) -> BaseException | None:
    if error.__cause__ is not None:
      return error.__cause__
    if error.__suppress_context__:
      return None
    return error.__context__
