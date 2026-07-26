from __future__ import annotations

import sqlite3
from contextlib import closing
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import BinaryIO, cast

import zstandard

from confer_document_worker.artifact.ArtifactFormat import (
  APPLICATION_ID,
  CONTAINER_SEPARATOR,
  HEADER,
  MAGIC,
  SCHEMA_VERSION as ARTIFACT_SCHEMA_VERSION,
)
from confer_document_worker.artifact.BlockRange import BlockRange
from confer_document_worker.artifact.ContainerRange import ContainerRange
from confer_document_worker.artifact.ArtifactBuildResult import ArtifactBuildResult
from confer_document_worker.domain.DocumentContent import DocumentContent


class ArtifactWriter:
  SCHEMA_VERSION = ARTIFACT_SCHEMA_VERSION
  DEFAULT_CHUNK_BYTES = 6_000
  DEFAULT_OVERLAP_BYTES = 600

  def __init__(self, chunk_bytes: int = DEFAULT_CHUNK_BYTES, overlap_bytes: int = DEFAULT_OVERLAP_BYTES):
    if chunk_bytes < 256:
      raise ValueError("chunk size must be at least 256 bytes")
    if overlap_bytes < 0 or overlap_bytes >= chunk_bytes:
      raise ValueError("chunk overlap must be non-negative and smaller than the chunk")
    self.chunk_bytes = chunk_bytes
    self.overlap_bytes = overlap_bytes

  def build(self, document: DocumentContent) -> bytes:
    with NamedTemporaryFile(prefix="confer-artifact-", suffix=".bin") as artifact:
      self.write(document, cast(BinaryIO, artifact))
      artifact.seek(0)
      return artifact.read()

  def write(self, document: DocumentContent, destination: BinaryIO) -> ArtifactBuildResult:
    with NamedTemporaryFile(prefix="confer-database-", suffix=".sqlite") as database:
      with closing(sqlite3.connect(database.name)) as connection:
        self._create_schema(connection)
        canonical, containers = self._write_content(connection, document)
        self._write_chunks(connection, canonical, containers)
        connection.execute("INSERT INTO document_text(id, text_utf8) VALUES (1, ?)", (canonical,))
        metadata = [
          ("schema_version", str(self.SCHEMA_VERSION)),
          ("source_sha256", document.source_sha256.lower()),
          ("media_type", document.media_type),
          ("title", document.title or ""),
          ("text_bytes", str(len(canonical))),
          ("container_count", str(len(document.containers))),
          ("outline_count", str(len(document.outline))),
        ]
        if document.media_type == "application/pdf":
          metadata.append(("page_count", str(len(document.containers))))
        connection.executemany("INSERT INTO metadata(key, value) VALUES (?, ?)", metadata)
        connection.commit()
        connection.execute("VACUUM")
      database.flush()
      database_size = Path(database.name).stat().st_size
      destination.seek(0)
      destination.truncate(0)
      destination.write(HEADER.pack(MAGIC, database_size))
      database.seek(0)
      zstandard.ZstdCompressor(level=6).copy_stream(database, destination)
      destination.flush()
      length = destination.tell()
      destination.seek(0)
      return ArtifactBuildResult(length, canonical)

  def _create_schema(self, connection: sqlite3.Connection) -> None:
    connection.executescript(f"""
      PRAGMA application_id = {APPLICATION_ID};
      PRAGMA user_version = {self.SCHEMA_VERSION};
      PRAGMA page_size = 4096;
      PRAGMA journal_mode = OFF;
      PRAGMA synchronous = OFF;

      CREATE TABLE metadata (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      ) WITHOUT ROWID;

      CREATE TABLE document_text (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        text_utf8 BLOB NOT NULL
      );

      CREATE TABLE containers (
        container_number INTEGER PRIMARY KEY,
        start_offset INTEGER NOT NULL,
        end_offset INTEGER NOT NULL,
        width REAL NOT NULL,
        height REAL NOT NULL,
        kind TEXT NOT NULL,
        label TEXT
      );

      CREATE TABLE blocks (
        container_number INTEGER NOT NULL,
        block_order INTEGER NOT NULL,
        start_offset INTEGER NOT NULL,
        end_offset INTEGER NOT NULL,
        x0 REAL NOT NULL,
        y0 REAL NOT NULL,
        x1 REAL NOT NULL,
        y1 REAL NOT NULL,
        source TEXT NOT NULL,
        confidence REAL NOT NULL,
        PRIMARY KEY (container_number, block_order)
      ) WITHOUT ROWID;

      CREATE TABLE regions (
        id TEXT PRIMARY KEY,
        container_number INTEGER NOT NULL,
        type TEXT NOT NULL,
        x0 REAL NOT NULL,
        y0 REAL NOT NULL,
        x1 REAL NOT NULL,
        y1 REAL NOT NULL,
        start_offset INTEGER,
        end_offset INTEGER,
        confidence REAL NOT NULL,
        renderable INTEGER NOT NULL,
        asset_media_type TEXT,
        asset BLOB
      ) WITHOUT ROWID;

      CREATE INDEX regions_offsets ON regions(start_offset, end_offset);
      CREATE INDEX regions_container ON regions(container_number);

      CREATE TABLE outline (
        ordinal INTEGER PRIMARY KEY,
        level INTEGER NOT NULL,
        title TEXT NOT NULL,
        container_number INTEGER NOT NULL
      );

      CREATE TABLE chunks (
        id INTEGER PRIMARY KEY,
        ordinal INTEGER NOT NULL UNIQUE,
        start_offset INTEGER NOT NULL,
        end_offset INTEGER NOT NULL,
        container_start INTEGER NOT NULL,
        container_end INTEGER NOT NULL
      );

      CREATE VIRTUAL TABLE chunk_fts USING fts5(
        text,
        tokenize='unicode61'
      );
    """)

  def _write_content(
    self,
    connection: sqlite3.Connection,
    document: DocumentContent,
  ) -> tuple[bytes, list[ContainerRange]]:
    canonical = bytearray()
    container_ranges: list[ContainerRange] = []

    for container_index, container in enumerate(document.containers):
      if container_index:
        canonical.extend(CONTAINER_SEPARATOR)
      container_start = len(canonical)
      container_blocks: list[BlockRange] = []

      for block_order, block in enumerate(container.blocks):
        if block_order and canonical and canonical[-1:] != b"\n":
          canonical.extend(b"\n")
        start = len(canonical)
        canonical.extend(block.text.encode("utf-8"))
        end = len(canonical)
        block_range = BlockRange(start, end)
        container_blocks.append(block_range)
        connection.execute(
          "INSERT INTO blocks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          (
            container.number,
            block_order,
            start,
            end,
            block.bounds.x0,
            block.bounds.y0,
            block.bounds.x1,
            block.bounds.y1,
            block.source.value,
            block.confidence,
          ),
        )

      container_end = len(canonical)
      container_range = ContainerRange(container.number, container_start, container_end)
      container_ranges.append(container_range)
      connection.execute(
        "INSERT INTO containers VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
          container.number,
          container_start,
          container_end,
          container.width,
          container.height,
          container.kind.value,
          container.label,
        ),
      )

      for region in container.regions:
        overlapping = [
          block_range
          for block_range, block in zip(container_blocks, container.blocks, strict=True)
          if block.bounds.intersects(region.bounds)
        ]
        region_start = min((block.start for block in overlapping), default=None)
        region_end = max((block.end for block in overlapping), default=None)
        connection.execute(
          "INSERT INTO regions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          (
            region.id,
            container.number,
            region.type.value,
            region.bounds.x0,
            region.bounds.y0,
            region.bounds.x1,
            region.bounds.y1,
            region_start,
            region_end,
            region.confidence,
            int(region.renderable),
            region.asset_media_type,
            region.asset,
          ),
        )

    connection.executemany(
      "INSERT INTO outline VALUES (?, ?, ?, ?)",
      (
        (ordinal, entry.level, entry.title, entry.container_number)
        for ordinal, entry in enumerate(document.outline)
      ),
    )

    return bytes(canonical), container_ranges

  def _write_chunks(
    self,
    connection: sqlite3.Connection,
    canonical: bytes,
    containers: list[ContainerRange],
  ) -> None:
    if not canonical:
      return

    start = 0
    ordinal = 0
    while start < len(canonical):
      end = self._chunk_end(canonical, start)
      container_numbers = [
        container.container_number
        for container in containers
        if container.start < end and container.end > start
      ]
      if not container_numbers:
        container_numbers = [self._nearest_container(containers, start)]
      chunk_id = ordinal + 1
      connection.execute(
        "INSERT INTO chunks VALUES (?, ?, ?, ?, ?, ?)",
        (chunk_id, ordinal, start, end, min(container_numbers), max(container_numbers)),
      )
      text = canonical[start:end].decode("utf-8")
      connection.execute("INSERT INTO chunk_fts(rowid, text) VALUES (?, ?)", (chunk_id, text))
      if end == len(canonical):
        break
      next_start = max(start + 1, end - self.overlap_bytes)
      start = self._utf8_boundary(canonical, next_start)
      ordinal += 1

  def _chunk_end(self, canonical: bytes, start: int) -> int:
    hard_end = min(len(canonical), start + self.chunk_bytes)
    if hard_end == len(canonical):
      return hard_end
    minimum = min(hard_end, start + self.chunk_bytes // 2)
    newline = canonical.rfind(b"\n", minimum, hard_end)
    if newline > start:
      return newline + 1
    space = canonical.rfind(b" ", minimum, hard_end)
    if space > start:
      return space + 1
    return self._utf8_boundary(canonical, hard_end)

  def _utf8_boundary(self, data: bytes, offset: int) -> int:
    while offset > 0 and offset < len(data) and data[offset] & 0xC0 == 0x80:
      offset -= 1
    return offset

  def _nearest_container(self, containers: list[ContainerRange], offset: int) -> int:
    if not containers:
      return 0
    preceding = [container.container_number for container in containers if container.start <= offset]
    return preceding[-1] if preceding else containers[0].container_number
