from __future__ import annotations


class WorkerResponse:
  def close(self) -> None:
    pass

  def __enter__(self) -> WorkerResponse:
    return self

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()
