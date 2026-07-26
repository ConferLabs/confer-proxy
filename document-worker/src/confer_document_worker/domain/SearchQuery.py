from dataclasses import dataclass


@dataclass(frozen=True)
class SearchQuery:
  all: tuple[str, ...]

  def __post_init__(self) -> None:
    if not 1 <= len(self.all) <= 8:
      raise ValueError("all must contain between 1 and 8 terms")
    if any(not term.strip() or len(term) > 256 for term in self.all):
      raise ValueError("search terms must contain between 1 and 256 characters")
