import subprocess

from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.extraction.pdf.RasterImage import RasterImage
from confer_document_worker.ocr.TesseractTsvParser import TesseractTsvParser


class TesseractOcrBackend:
  def __init__(
    self,
    parser: TesseractTsvParser,
    executable: str = "tesseract",
    language: str = "eng",
    timeout_seconds: int = 60,
    max_concurrency: int = 8,
  ):
    self.parser = parser
    self.executable = executable
    self.language = language
    self.timeout_seconds = timeout_seconds
    self.max_concurrency = max_concurrency

  def check(self) -> None:
    try:
      completed = subprocess.run(
        [self.executable, "--version"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
        timeout=self.timeout_seconds,
      )
    except subprocess.TimeoutExpired as error:
      raise RuntimeError("OCR executable check exceeded its time limit") from error
    except OSError as error:
      raise RuntimeError("OCR executable could not be started") from error
    if completed.returncode != 0:
      raise RuntimeError("OCR executable check failed")

  def extract(self, image: RasterImage) -> tuple[TextBlock, ...]:
    ppm = f"P6\n{image.width} {image.height}\n255\n".encode("ascii") + image.samples
    try:
      completed = subprocess.run(
        [
          self.executable,
          "stdin",
          "stdout",
          "--dpi",
          "200",
          "-l",
          self.language,
          "--psm",
          "3",
          "tsv",
        ],
        input=ppm,
        capture_output=True,
        check=False,
        timeout=self.timeout_seconds,
      )
    except subprocess.TimeoutExpired as error:
      raise RuntimeError("OCR exceeded its time limit") from error
    except OSError as error:
      raise RuntimeError("OCR executable could not be started") from error
    if completed.returncode != 0:
      raise RuntimeError("OCR failed")
    return self.parser.parse(completed.stdout.decode("utf-8", errors="replace"), image)
