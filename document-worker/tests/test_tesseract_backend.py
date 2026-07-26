import sys
from pathlib import Path

import pytest

from confer_document_worker.ocr.TesseractOcrBackend import TesseractOcrBackend
from confer_document_worker.extraction.pdf.RasterImage import RasterImage
from confer_document_worker.ocr.TesseractTsvParser import TesseractTsvParser


TSV = """level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
5\t1\t1\t1\t1\t1\t10\t20\t30\t10\t95.0\tAnalyte
5\t1\t1\t1\t1\t2\t50\t20\t20\t10\t85.0\t500
5\t1\t1\t1\t1\t3\t75\t20\t15\t10\t90.0\tng/dL
5\t1\t1\t1\t2\t1\t10\t50\t30\t10\t80.0\tReference
5\t1\t1\t1\t2\t2\t50\t50\t40\t10\t70.0\t300-1000
"""


def python_executable(directory: Path, source: str) -> str:
  executable = directory / "ocr-executable"
  executable.write_text(
    f"#!{sys.executable}\n{source}\n",
    encoding="utf-8",
  )
  executable.chmod(0o700)
  return str(executable)


def test_groups_words_into_lines_and_maps_coordinates() -> None:
  image = RasterImage(
    width=100,
    height=200,
    samples=bytes(100 * 200 * 3),
    page_width=200,
    page_height=400,
  )

  blocks = TesseractTsvParser().parse(TSV, image)

  assert [block.text for block in blocks] == [
    "Analyte 500 ng/dL\n",
    "Reference 300-1000\n",
  ]
  assert blocks[0].bounds.x0 == 20
  assert blocks[0].bounds.y0 == 40
  assert blocks[0].bounds.x1 == 180
  assert blocks[0].bounds.y1 == 60
  assert blocks[0].confidence == 0.9


def test_check_accepts_an_executable_that_starts_successfully() -> None:
  TesseractOcrBackend(
    TesseractTsvParser(),
    executable=sys.executable,
  ).check()


def test_check_rejects_an_executable_that_cannot_be_started() -> None:
  with pytest.raises(RuntimeError, match="could not be started"):
    TesseractOcrBackend(
      TesseractTsvParser(),
      executable="confer-missing-ocr-executable",
    ).check()


def test_check_rejects_an_executable_that_returns_an_error(tmp_path: Path) -> None:
  executable = python_executable(tmp_path, "raise SystemExit(1)")

  with pytest.raises(RuntimeError, match="check failed"):
    TesseractOcrBackend(
      TesseractTsvParser(),
      executable=executable,
    ).check()


def test_check_rejects_an_executable_that_times_out(tmp_path: Path) -> None:
  executable = python_executable(
    tmp_path,
    "import time\ntime.sleep(60)",
  )

  with pytest.raises(RuntimeError, match="exceeded its time limit"):
    TesseractOcrBackend(
      TesseractTsvParser(),
      executable=executable,
      timeout_seconds=0.01,
    ).check()
