import os
from contextlib import ExitStack
from pathlib import Path
from tempfile import TemporaryDirectory


class DocumentPathAlias:
  """Gives a private spool inode the trusted suffix required by some parsers."""

  def __init__(self, source: Path, suffix: str):
    if not suffix.startswith(".") or not suffix[1:].isalnum():
      raise ValueError("document path alias suffix is invalid")
    with ExitStack() as resources:
      directory = resources.enter_context(
        TemporaryDirectory(prefix="confer-document-alias-", dir=source.parent),
      )
      self.path = Path(directory) / f"document{suffix.casefold()}"
      os.link(source, self.path, follow_symlinks=False)
      self._resources = resources.pop_all()

  def close(self) -> None:
    self._resources.close()

  def __enter__(self) -> Path:
    return self.path

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()
