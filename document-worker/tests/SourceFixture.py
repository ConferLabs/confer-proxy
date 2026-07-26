import hashlib
from contextlib import ExitStack
from pathlib import Path
from tempfile import NamedTemporaryFile

from confer_document_worker.domain.DocumentSource import DocumentSource


class SourceFixture:
  def __init__(self, content: bytes, suffix: str = ".document"):
    with ExitStack() as resources:
      temporary = resources.enter_context(
        NamedTemporaryFile(prefix="confer-test-source-", suffix=suffix),
      )
      temporary.write(content)
      temporary.flush()
      temporary.seek(0)
      self.source = DocumentSource(
        Path(temporary.name),
        len(content),
        hashlib.sha256(content).hexdigest(),
      )
      self._resources = resources.pop_all()

  def close(self) -> None:
    self._resources.close()

  def __enter__(self) -> DocumentSource:
    return self.source

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()
