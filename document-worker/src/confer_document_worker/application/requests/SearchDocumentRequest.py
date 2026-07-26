from dataclasses import dataclass
from typing import ClassVar

from confer_document_worker.application.errors.InvalidWorkerRequestError import InvalidWorkerRequestError
from confer_document_worker.application.requests.DocumentSessionRequest import DocumentSessionRequest
from confer_document_worker.domain.SearchQuery import SearchQuery


@dataclass(frozen=True)
class SearchDocumentRequest(DocumentSessionRequest):
  DEFAULT_SNIPPET_CHARACTERS: ClassVar[int] = 1_200
  MIN_SNIPPET_CHARACTERS: ClassVar[int] = 64
  MAX_SNIPPET_CHARACTERS: ClassVar[int] = 4_000

  document_id: str
  queries: tuple[SearchQuery, ...]
  limit: int
  snippet_characters: int = DEFAULT_SNIPPET_CHARACTERS

  def __post_init__(self) -> None:
    if not self.document_id:
      raise InvalidWorkerRequestError("document_id must be a non-empty string")
    if not 1 <= len(self.queries) <= 8:
      raise InvalidWorkerRequestError("queries must contain between 1 and 8 searches")
    if self.limit < 1 or self.limit > 50:
      raise InvalidWorkerRequestError("search limit must be between 1 and 50")
    if (
      self.snippet_characters < self.MIN_SNIPPET_CHARACTERS
      or self.snippet_characters > self.MAX_SNIPPET_CHARACTERS
    ):
      raise InvalidWorkerRequestError(
        "snippet_characters must be between 64 and 4000"
      )
