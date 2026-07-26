from __future__ import annotations

import io
import re
import uuid
from contextlib import ExitStack
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import BinaryIO, cast

import zstandard

from confer_document_worker.artifact.ArtifactDatabase import ArtifactDatabase
from confer_document_worker.artifact.ArtifactFormat import HEADER, MAGIC, MAX_DATABASE_BYTES
from confer_document_worker.artifact.ArtifactMetadata import ArtifactMetadata
from confer_document_worker.artifact.StoredRegion import StoredRegion
from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.ContainerKind import ContainerKind
from confer_document_worker.domain.ContainerReference import ContainerReference
from confer_document_worker.domain.OutlineEntry import OutlineEntry
from confer_document_worker.domain.RegionReference import RegionReference
from confer_document_worker.domain.SearchHit import SearchHit
from confer_document_worker.domain.SearchQuery import SearchQuery
from confer_document_worker.domain.TextWindow import TextWindow
from confer_document_worker.domain.VisualRegionType import VisualRegionType


MatchSpan = tuple[int, int]


class ArtifactReader:
  FTS_SNIPPET_TOKENS = 64

  def __init__(self, artifact: bytes | Path):
    with ExitStack() as resources:
      database = resources.enter_context(
        NamedTemporaryFile(prefix="confer-artifact-", suffix=".sqlite"),
      )
      if isinstance(artifact, bytes):
        source: BinaryIO = io.BytesIO(artifact)
        self._unpack(source, cast(BinaryIO, database))
      else:
        with artifact.open("rb") as source:
          self._unpack(source, cast(BinaryIO, database))
      database.flush()
      artifact_database = resources.enter_context(ArtifactDatabase(Path(database.name)))
      self.connection = artifact_database.connection
      self.text_bytes = artifact_database.text_bytes
      self._resources = resources.pop_all()

  def close(self) -> None:
    self._resources.close()

  def __enter__(self) -> ArtifactReader:
    return self

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()

  def search(
    self,
    queries: tuple[SearchQuery, ...],
    limit: int = 8,
    snippet_chars: int = 1_200,
  ) -> tuple[SearchHit, ...]:
    if limit < 1 or limit > 51:
      raise ValueError("search limit must be between 1 and 51")
    if snippet_chars < 1:
      raise ValueError("snippet length must be positive")
    match_query = self._match_query(queries)
    marker = uuid.uuid4().hex
    start_marker = f"\ue000{marker}\ue001"
    end_marker = f"\ue002{marker}\ue003"
    rows = self.connection.execute(
      """
        SELECT chunks.id,
               chunks.ordinal,
               chunks.start_offset,
               chunks.end_offset,
               chunks.container_start,
               chunks.container_end,
               chunk_fts.text AS indexed_text,
               snippet(chunk_fts, 0, ?, ?, '', ?) AS matched_fragment,
               bm25(chunk_fts) AS score
        FROM chunk_fts
        JOIN chunks ON chunks.id = chunk_fts.rowid
        WHERE chunk_fts MATCH ?
        ORDER BY score, chunks.ordinal
        LIMIT ?
      """,
      (
        start_marker,
        end_marker,
        self.FTS_SNIPPET_TOKENS,
        match_query,
        limit,
      ),
    ).fetchall()
    return tuple(
      SearchHit(
        chunk_id=row["id"],
        ordinal=row["ordinal"],
        score=row["score"],
        container_start=row["container_start"],
        container_end=row["container_end"],
        text=self._fts_snippet(
          row["indexed_text"],
          row["matched_fragment"],
          start_marker,
          end_marker,
          snippet_chars,
        ),
        regions=self._regions(row["start_offset"], row["end_offset"], row["container_start"], row["container_end"]),
        containers=self._containers(row["container_start"], row["container_end"]),
      )
      for row in rows
    )

  def read_containers(
    self,
    container_number: int,
    container_count: int = 1,
    max_bytes: int = 64_000,
  ) -> TextWindow:
    if container_number < 0:
      raise ValueError("container number cannot be negative")
    if container_count < 1 or container_count > 20:
      raise ValueError("container count must be between 1 and 20")
    total_containers = self.connection.execute(
      "SELECT count(*) FROM containers",
    ).fetchone()[0]
    if container_number >= total_containers:
      raise KeyError(f"unknown container: {container_number}")
    rows = self.connection.execute(
      """
        SELECT container_number, start_offset, end_offset
        FROM containers
        WHERE container_number BETWEEN ? AND ?
        ORDER BY container_number
      """,
      (container_number, min(total_containers - 1, container_number + container_count - 1)),
    ).fetchall()
    container_start = rows[0]["container_number"]
    container_end = rows[-1]["container_number"]
    start = rows[0]["start_offset"]
    end = rows[-1]["end_offset"]
    if end - start > max_bytes:
      raise ValueError("requested text window exceeds maximum size")
    return TextWindow(
      container_start=container_start,
      container_end=container_end,
      total_containers=total_containers,
      text=self._text(start, end).decode("utf-8"),
      regions=self._regions(start, end, container_start, container_end),
      containers=self._containers(container_start, container_end),
    )

  def full_text(self, max_bytes: int) -> str:
    if self.text_bytes > max_bytes:
      raise ValueError("document text exceeds full-text budget")
    return self._text(0, self.text_bytes).decode("utf-8")

  def metadata(self) -> ArtifactMetadata:
    values = {
      row["key"]: row["value"]
      for row in self.connection.execute("SELECT key, value FROM metadata")
    }
    required = {
      "schema_version",
      "source_sha256",
      "media_type",
      "title",
      "text_bytes",
      "container_count",
      "outline_count",
    }
    if not required.issubset(values):
      raise ValueError("artifact metadata is incomplete")
    try:
      return ArtifactMetadata(
        schema_version=int(values["schema_version"]),
        source_sha256=values["source_sha256"],
        media_type=values["media_type"],
        title=values["title"] or None,
        text_bytes=int(values["text_bytes"]),
        container_count=int(values["container_count"]),
        outline_count=int(values["outline_count"]),
        page_count=int(values["page_count"]) if "page_count" in values else None,
      )
    except ValueError as error:
      raise ValueError("artifact metadata is invalid") from error

  def outline(self, limit: int = 1_000) -> tuple[OutlineEntry, ...]:
    rows = self.connection.execute(
      "SELECT level, title, container_number FROM outline ORDER BY ordinal LIMIT ?",
      (limit,),
    ).fetchall()
    return tuple(
      OutlineEntry(row["level"], row["title"], row["container_number"])
      for row in rows
    )

  def first_chunk_id(self) -> int | None:
    row = self.connection.execute(
      "SELECT id FROM chunks ORDER BY ordinal LIMIT 1",
    ).fetchone()
    return None if row is None else int(row["id"])

  def full_text_if_small(self, max_bytes: int) -> str | None:
    if self.text_bytes > max_bytes:
      return None
    return self._text(0, self.text_bytes).decode("utf-8")

  def labeled_containers(self, limit: int = 500) -> tuple[ContainerReference, ...]:
    rows = self.connection.execute(
      "SELECT container_number, kind, label FROM containers WHERE label IS NOT NULL ORDER BY container_number LIMIT ?",
      (limit,),
    ).fetchall()
    return tuple(
      ContainerReference(
        row["container_number"],
        ContainerKind(row["kind"]),
        row["label"],
      )
      for row in rows
    )

  def containers(self, limit: int = 5_000) -> tuple[ContainerReference, ...]:
    rows = self.connection.execute(
      "SELECT container_number, kind, label FROM containers ORDER BY container_number LIMIT ?",
      (limit,),
    ).fetchall()
    return tuple(
      ContainerReference(
        row["container_number"],
        ContainerKind(row["kind"]),
        row["label"],
      )
      for row in rows
    )

  def region(self, region_id: str) -> StoredRegion:
    row = self.connection.execute(
      "SELECT * FROM regions WHERE id = ?",
      (region_id,),
    ).fetchone()
    if row is None:
      raise KeyError(f"unknown region: {region_id}")
    return StoredRegion(
      id=row["id"],
      container_number=row["container_number"],
      type=VisualRegionType(row["type"]),
      bounds=Bounds(row["x0"], row["y0"], row["x1"], row["y1"]),
      confidence=row["confidence"],
      renderable=bool(row["renderable"]),
      asset_media_type=row["asset_media_type"],
      asset=bytes(row["asset"]) if row["asset"] is not None else None,
    )

  def _regions(self, start: int, end: int, container_start: int, container_end: int) -> tuple[RegionReference, ...]:
    rows = self.connection.execute(
      """
        SELECT id, container_number, type, confidence, renderable
        FROM regions
        WHERE (start_offset IS NOT NULL AND start_offset < ? AND end_offset > ?)
           OR (start_offset IS NULL AND container_number BETWEEN ? AND ?)
        ORDER BY container_number, id
        LIMIT 20
      """,
      (end, start, container_start, container_end),
    ).fetchall()
    return tuple(
      RegionReference(
        id=row["id"],
        container_number=row["container_number"],
        type=VisualRegionType(row["type"]),
        confidence=row["confidence"],
        renderable=bool(row["renderable"]),
      )
      for row in rows
    )

  def _containers(self, container_start: int, container_end: int) -> tuple[ContainerReference, ...]:
    rows = self.connection.execute(
      """
        SELECT container_number, kind, label
        FROM containers
        WHERE container_number BETWEEN ? AND ?
        ORDER BY container_number
      """,
      (container_start, container_end),
    ).fetchall()
    return tuple(
      ContainerReference(row["container_number"], ContainerKind(row["kind"]), row["label"])
      for row in rows
    )

  def _match_query(
    self,
    queries: tuple[SearchQuery, ...],
  ) -> str:
    alternatives: list[str] = []
    for query in queries:
      required: list[str] = []
      searchable = True
      for literal in query.all:
        tokens = re.findall(r"[^\W_]+", literal.casefold(), flags=re.UNICODE)
        if not tokens:
          searchable = False
          break
        phrase = " ".join(tokens)
        if phrase not in required:
          required.append(phrase)
      if searchable and required:
        expression = " AND ".join(f'"{phrase}"' for phrase in required)
        alternative = f"({expression})"
        if alternative not in alternatives:
          alternatives.append(alternative)
    if not alternatives:
      raise ValueError("search query has no searchable terms")
    return " OR ".join(alternatives)

  def _fts_snippet(
    self,
    text: str,
    marked_fragment: str,
    start_marker: str,
    end_marker: str,
    limit: int,
  ) -> str:
    fragment, match = self._marked_fragment(
      marked_fragment,
      start_marker,
      end_marker,
    )
    fragment_offset = text.find(fragment)
    if fragment_offset < 0:
      return self._bounded_snippet(fragment, match[0], match[1], limit)
    return self._bounded_snippet(
      text,
      fragment_offset + match[0],
      fragment_offset + match[1],
      limit,
    )

  def _marked_fragment(
    self,
    marked: str,
    start_marker: str,
    end_marker: str,
  ) -> tuple[str, MatchSpan]:
    plain: list[str] = []
    matches: list[MatchSpan] = []
    match_start: int | None = None
    cursor = 0
    plain_length = 0

    while cursor < len(marked):
      if marked.startswith(start_marker, cursor):
        if match_start is not None:
          raise ValueError("FTS snippet markers are invalid")
        match_start = plain_length
        cursor += len(start_marker)
      elif marked.startswith(end_marker, cursor):
        if match_start is None:
          raise ValueError("FTS snippet markers are invalid")
        matches.append((match_start, plain_length))
        match_start = None
        cursor += len(end_marker)
      else:
        plain.append(marked[cursor])
        plain_length += 1
        cursor += 1

    if match_start is not None or not matches:
      raise ValueError("FTS snippet markers are invalid")
    return "".join(plain), (matches[0][0], matches[-1][1])

  def _bounded_snippet(
    self,
    text: str,
    match_start: int,
    match_end: int,
    limit: int,
  ) -> str:
    if len(text) <= limit:
      return text
    if limit < 3:
      return text[:limit]

    match_start = max(0, min(len(text), match_start))
    match_end = max(match_start, min(len(text), match_end))
    content_limit = limit - 2
    match_length = match_end - match_start
    context = max(0, content_limit - match_length)
    snippet_start = max(0, match_start - context // 3)
    snippet_end = min(len(text), snippet_start + content_limit)
    if snippet_end < match_end:
      snippet_end = match_end
      snippet_start = max(0, snippet_end - content_limit)

    prefix = "…" if snippet_start else ""
    suffix = "…" if snippet_end < len(text) else ""
    content_limit = limit - len(prefix) - len(suffix)
    snippet_end = min(len(text), snippet_start + content_limit)
    if snippet_end < match_end:
      snippet_end = match_end
      snippet_start = max(0, snippet_end - content_limit)

    bounded_start = self._character_boundary(text, snippet_start, forward=True)
    if bounded_start <= match_start:
      snippet_start = bounded_start
    bounded_end = self._character_boundary(text, snippet_end, forward=False)
    if bounded_end >= match_end:
      snippet_end = bounded_end
    return prefix + text[snippet_start:snippet_end] + suffix

  def _character_boundary(self, text: str, offset: int, forward: bool) -> int:
    if offset <= 0 or offset >= len(text):
      return max(0, min(len(text), offset))
    whitespace = re.compile(r"\s")
    if forward:
      while offset < len(text) and not whitespace.match(text[offset]):
        offset += 1
    else:
      while offset > 0 and not whitespace.match(text[offset - 1]):
        offset -= 1
    return offset

  def _text(self, start: int, end: int) -> bytes:
    if start < 0 or end < start or end > self.text_bytes:
      raise ValueError("artifact text range is invalid")
    row = self.connection.execute(
      "SELECT substr(text_utf8, ?, ?) FROM document_text WHERE id = 1",
      (start + 1, end - start),
    ).fetchone()
    if row is None:
      raise ValueError("artifact canonical text is missing")
    return bytes(row[0])

  def _unpack(self, artifact: BinaryIO, database: BinaryIO) -> None:
    encoded_header = artifact.read(HEADER.size)
    if len(encoded_header) != HEADER.size:
      raise ValueError("artifact is truncated")
    magic, expected_size = HEADER.unpack(encoded_header)
    if magic != MAGIC:
      raise ValueError("artifact magic is invalid")
    if expected_size > MAX_DATABASE_BYTES:
      raise ValueError("artifact database exceeds maximum size")
    try:
      written = 0
      with zstandard.ZstdDecompressor().stream_reader(artifact) as reader:
        while chunk := reader.read(64 * 1024):
          written += len(chunk)
          if written > expected_size or written > MAX_DATABASE_BYTES:
            raise ValueError("artifact database exceeds declared size")
          database.write(chunk)
    except zstandard.ZstdError as error:
      raise ValueError("artifact compression is invalid") from error
    if written != expected_size:
      raise ValueError("artifact database size does not match header")
