from io import BytesIO
import json

from confer_document_worker.worker.WorkerErrorReporter import WorkerErrorReporter


def raise_private_error() -> None:
  raise ValueError("private root cause")


def wrap_private_error() -> None:
  try:
    raise_private_error()
  except ValueError as error:
    raise RuntimeError("private outer error") from error


def raise_recursively(depth: int) -> None:
  if depth == 0:
    raise RuntimeError("private recursive error")
  raise_recursively(depth - 1)


def diagnostic(output: BytesIO) -> dict[str, object]:
  encoded = output.getvalue()
  assert encoded.startswith(WorkerErrorReporter.PREFIX)
  return json.loads(encoded.removeprefix(WorkerErrorReporter.PREFIX))


def test_reports_sanitized_traceback_without_user_data() -> None:
  output = BytesIO()
  reporter = WorkerErrorReporter(output)

  try:
    wrap_private_error()
  except RuntimeError as error:
    reporter.report(object(), error)

  report = diagnostic(output)
  exception_chain = report["exception_chain"]
  assert isinstance(exception_chain, list)
  assert [exception["type"] for exception in exception_chain] == [
    "builtins.RuntimeError",
    "builtins.ValueError",
  ]
  assert exception_chain[0]["frames"][-1].startswith(
    "test_worker_error_reporter.wrap_private_error:",
  )
  assert exception_chain[1]["frames"][-1].startswith(
    "test_worker_error_reporter.raise_private_error:",
  )
  assert report["request"] == "object"
  assert report["chain_truncated"] is False

  encoded = output.getvalue().decode("ascii")
  assert "private root cause" not in encoded
  assert "private outer error" not in encoded
  assert "Traceback" not in encoded
  assert ".py" not in encoded


def test_reports_errors_without_tracebacks() -> None:
  output = BytesIO()

  WorkerErrorReporter(output).report(object(), RuntimeError("private content"))

  assert diagnostic(output) == {
    "request": "object",
    "exception_chain": [{
      "type": "builtins.RuntimeError",
      "frames": [],
      "frames_truncated": False,
    }],
    "chain_truncated": False,
  }


def test_limits_traceback_depth() -> None:
  output = BytesIO()

  try:
    raise_recursively(WorkerErrorReporter.MAX_FRAMES + 10)
  except RuntimeError as error:
    WorkerErrorReporter(output).report(object(), error)

  report = diagnostic(output)
  exception_chain = report["exception_chain"]
  assert isinstance(exception_chain, list)
  assert len(exception_chain[0]["frames"]) == WorkerErrorReporter.MAX_FRAMES
  assert exception_chain[0]["frames_truncated"] is True


def test_limits_exception_chain_depth() -> None:
  output = BytesIO()
  error = RuntimeError("private root error")
  for _ in range(WorkerErrorReporter.MAX_EXCEPTIONS):
    wrapper = RuntimeError("private wrapper error")
    wrapper.__cause__ = error
    error = wrapper

  WorkerErrorReporter(output).report(object(), error)

  report = diagnostic(output)
  exception_chain = report["exception_chain"]
  assert isinstance(exception_chain, list)
  assert len(exception_chain) == WorkerErrorReporter.MAX_EXCEPTIONS
  assert report["chain_truncated"] is True


def test_ignores_suppressed_exception_context() -> None:
  output = BytesIO()
  error = RuntimeError("private outer error")
  error.__context__ = ValueError("private hidden context")
  error.__suppress_context__ = True

  WorkerErrorReporter(output).report(object(), error)

  report = diagnostic(output)
  exception_chain = report["exception_chain"]
  assert isinstance(exception_chain, list)
  assert len(exception_chain) == 1
  assert report["chain_truncated"] is False
  assert b"private hidden context" not in output.getvalue()


def test_limits_cyclic_exception_chains() -> None:
  output = BytesIO()
  error = RuntimeError("private cyclic error")
  error.__cause__ = error

  WorkerErrorReporter(output).report(object(), error)

  report = diagnostic(output)
  exception_chain = report["exception_chain"]
  assert isinstance(exception_chain, list)
  assert len(exception_chain) == 1
  assert report["chain_truncated"] is True


def test_reporting_failure_does_not_replace_the_worker_failure() -> None:
  class UnavailableOutput(BytesIO):
    def write(self, _value: bytes) -> int:
      raise OSError("journal is unavailable")

  WorkerErrorReporter(UnavailableOutput()).report(
    object(),
    RuntimeError("private content"),
  )
