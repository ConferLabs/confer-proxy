import pymupdf

from confer_document_worker.domain.Bounds import Bounds


class TableDetector:
  MIN_TABLE_COLUMNS = 2
  MAX_RULE_ALIGNMENT_ERROR = 1
  MIN_HORIZONTAL_RULE_LENGTH = 40
  MIN_VERTICAL_RULE_LENGTH = 20
  MIN_HORIZONTAL_RULES = 3
  MIN_VERTICAL_RULES = 2
  SAME_REGION_OVERLAP = 0.8

  def __init__(
    self,
    column_bin_points: int = 8,
    min_column_occurrences: int = 4,
    min_cell_gap_points: int = 18,
    min_table_lines: int = 4,
  ):
    self.column_bin_points = column_bin_points
    self.min_column_occurrences = min_column_occurrences
    self.min_cell_gap_points = min_cell_gap_points
    self.min_table_lines = min_table_lines

  def detect(self, page: pymupdf.Page) -> tuple[Bounds, ...]:
    words = page.get_text("words", sort=True)
    aligned_bounds = self._aligned_bounds(words, page.rect)
    ruled_bounds = self._ruled_bounds(page)
    if ruled_bounds is None and aligned_bounds is None:
      return ()

    candidates = [bounds for bounds in (ruled_bounds, aligned_bounds) if bounds is not None]
    if len(candidates) == 2 and self._mostly_same(candidates[0], candidates[1]):
      return (candidates[0],)
    return tuple(candidates)

  def bounds(self, value: object, page_rect: pymupdf.Rect) -> Bounds | None:
    rect = pymupdf.Rect(value) & page_rect
    if rect.is_empty or rect.width <= 0 or rect.height <= 0:
      return None
    return Bounds(
      round(max(0.0, rect.x0), 2),
      round(max(0.0, rect.y0), 2),
      round(max(0.0, rect.x1), 2),
      round(max(0.0, rect.y1), 2),
    )

  def _aligned_bounds(self, words: list[tuple], page_rect: pymupdf.Rect) -> Bounds | None:
    lines: dict[tuple[int, int], list[tuple]] = {}
    for word in words:
      lines.setdefault((word[5], word[6]), []).append(word)
    candidate_lines: list[tuple[list[tuple], list[float]]] = []
    for words_in_line in lines.values():
      ordered = sorted(words_in_line, key=lambda word: word[0])
      cell_starts = [float(ordered[0][0])] if ordered else []
      for previous, current in zip(ordered, ordered[1:], strict=False):
        if current[0] - previous[2] >= self.min_cell_gap_points:
          cell_starts.append(float(current[0]))
      if len(cell_starts) >= 2:
        candidate_lines.append((ordered, cell_starts))
    if len(candidate_lines) < self.min_table_lines:
      return None

    bin_counts: dict[int, int] = {}
    for _, cell_starts in candidate_lines:
      seen = {round(start / self.column_bin_points) for start in cell_starts}
      for column in seen:
        bin_counts[column] = bin_counts.get(column, 0) + 1
    stable_columns = {
      column for column, count in bin_counts.items()
      if count >= self.min_column_occurrences
    }
    if len(stable_columns) < self.MIN_TABLE_COLUMNS:
      return None

    aligned_lines = [
      line
      for line, cell_starts in candidate_lines
      if (
        len({round(start / self.column_bin_points) for start in cell_starts} & stable_columns)
        >= self.MIN_TABLE_COLUMNS
      )
    ]
    if len(aligned_lines) < self.min_table_lines:
      return None
    aligned_words = [word for line in aligned_lines for word in line]
    rect = pymupdf.Rect(
      min(word[0] for word in aligned_words),
      min(word[1] for word in aligned_words),
      max(word[2] for word in aligned_words),
      max(word[3] for word in aligned_words),
    )
    return self.bounds(rect, page_rect)

  def _ruled_bounds(self, page: pymupdf.Page) -> Bounds | None:
    horizontal = 0
    vertical = 0
    x_values: list[float] = []
    y_values: list[float] = []
    for drawing in page.get_drawings():
      for item in drawing.get("items", ()):
        if item[0] == "l":
          first, second = item[1], item[2]
          if (
            abs(first.y - second.y) <= self.MAX_RULE_ALIGNMENT_ERROR
            and abs(first.x - second.x) >= self.MIN_HORIZONTAL_RULE_LENGTH
          ):
            horizontal += 1
            x_values.extend((first.x, second.x))
            y_values.extend((first.y, second.y))
          elif (
            abs(first.x - second.x) <= self.MAX_RULE_ALIGNMENT_ERROR
            and abs(first.y - second.y) >= self.MIN_VERTICAL_RULE_LENGTH
          ):
            vertical += 1
            x_values.extend((first.x, second.x))
            y_values.extend((first.y, second.y))
        elif item[0] == "re":
          item_rect = item[1]
          if item_rect.width >= self.MIN_HORIZONTAL_RULE_LENGTH:
            horizontal += 2
          if item_rect.height >= self.MIN_VERTICAL_RULE_LENGTH:
            vertical += 2
          x_values.extend((item_rect.x0, item_rect.x1))
          y_values.extend((item_rect.y0, item_rect.y1))
    if (
      horizontal < self.MIN_HORIZONTAL_RULES
      or vertical < self.MIN_VERTICAL_RULES
      or not x_values
      or not y_values
    ):
      return None
    return self.bounds(
      pymupdf.Rect(min(x_values), min(y_values), max(x_values), max(y_values)),
      page.rect,
    )

  def _mostly_same(self, first: Bounds, second: Bounds) -> bool:
    intersection_width = max(0.0, min(first.x1, second.x1) - max(first.x0, second.x0))
    intersection_height = max(0.0, min(first.y1, second.y1) - max(first.y0, second.y0))
    intersection = intersection_width * intersection_height
    first_area = (first.x1 - first.x0) * (first.y1 - first.y0)
    second_area = (second.x1 - second.x0) * (second.y1 - second.y0)
    return (
      intersection / max(1.0, min(first_area, second_area))
      >= self.SAME_REGION_OVERLAP
    )
