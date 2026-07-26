from __future__ import annotations

import hashlib
from io import BytesIO
import sqlite3
from tempfile import NamedTemporaryFile

import pytest
import zstandard

from confer_document_worker.artifact.ArtifactFormat import (
  HEADER,
  MAGIC,
  MAX_DATABASE_BYTES,
  MIN_SUPPORTED_SCHEMA_VERSION,
  SCHEMA_VERSION,
)
from confer_document_worker.artifact.ArtifactReader import ArtifactReader
from confer_document_worker.artifact.ArtifactWriter import ArtifactWriter
from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.SearchQuery import SearchQuery
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion
from confer_document_worker.domain.VisualRegionType import VisualRegionType


def sample_document() -> DocumentContent:
  source = b"synthetic PDF"
  return DocumentContent(
    source_sha256=hashlib.sha256(source).hexdigest(),
    title="Medical history",
    containers=(
      DocumentContainer(
        number=0,
        width=612,
        height=792,
        blocks=(
          TextBlock("Patient summary and prior history.", Bounds(20, 20, 580, 60)),
          TextBlock("The first laboratory panel was collected in January.", Bounds(20, 80, 580, 120)),
        ),
      ),
      DocumentContainer(
        number=1,
        width=612,
        height=792,
        blocks=(
          TextBlock("Analyte 410 ng/dL reference range 300-1000", Bounds(40, 100, 560, 130)),
          TextBlock("Vitamin D 32 ng/mL reference range 30-100", Bounds(40, 135, 560, 165)),
        ),
        regions=(
          VisualRegion("region_lab", VisualRegionType.TABLE, Bounds(30, 85, 570, 180), 0.75),
        ),
      ),
      DocumentContainer(
        number=2,
        width=612,
        height=792,
        blocks=(
          TextBlock("Follow-up analyte 525 ng/dL in June.", Bounds(40, 100, 560, 130)),
          TextBlock("The result increased compared with January.", Bounds(40, 150, 560, 180)),
        ),
        regions=(
          VisualRegion("region_chart", VisualRegionType.CHART, Bounds(30, 200, 570, 500), 1.0),
        ),
      ),
    ),
  )


def mutate_database(artifact: bytes, script: str) -> bytes:
  magic, expected_size = HEADER.unpack(artifact[:HEADER.size])
  assert magic == MAGIC
  source = BytesIO(artifact[HEADER.size:])
  with zstandard.ZstdDecompressor().stream_reader(source) as reader:
    database = reader.read(MAX_DATABASE_BYTES + 1)
  assert len(database) == expected_size

  with NamedTemporaryFile(suffix=".sqlite") as temporary:
    temporary.write(database)
    temporary.flush()
    connection = sqlite3.connect(temporary.name)
    try:
      connection.executescript(script)
      connection.commit()
    finally:
      connection.close()
    temporary.seek(0)
    database = temporary.read()

  destination = BytesIO()
  destination.write(HEADER.pack(MAGIC, len(database)))
  zstandard.ZstdCompressor(level=6).copy_stream(BytesIO(database), destination)
  return destination.getvalue()


def test_searches_canonical_text_and_returns_visual_regions() -> None:
  artifact = ArtifactWriter(chunk_bytes=256, overlap_bytes=32).build(sample_document())

  with ArtifactReader(artifact) as reader:
    hits = reader.search((SearchQuery(("analyte",)),), limit=5)

    assert hits
    assert "analyte" in hits[0].text.casefold()
    assert any(region.id == "region_lab" for hit in hits for region in hit.regions)


def test_search_snippets_do_not_exceed_the_requested_length() -> None:
  document = DocumentContent(
    source_sha256="0" * 64,
    containers=(
      DocumentContainer(
        number=0,
        width=612,
        height=792,
        blocks=(TextBlock(
          "prefix " * 30 + "analyte " + "suffix " * 30,
          Bounds(0, 0, 1, 1),
        ),),
      ),
    ),
  )
  artifact = ArtifactWriter(chunk_bytes=2_048, overlap_bytes=0).build(document)

  with ArtifactReader(artifact) as reader:
    hit = reader.search(
      (SearchQuery(("analyte",)),),
      snippet_chars=64,
    )[0]

  assert len(hit.text) <= 64
  assert "analyte" in hit.text
  assert hit.text.startswith("…")
  assert hit.text.endswith("…")


def test_search_snippets_are_anchored_to_the_matching_phrase() -> None:
  document = DocumentContent(
    source_sha256="0" * 64,
    containers=(
      DocumentContainer(
        number=0,
        width=612,
        height=792,
        blocks=(TextBlock(
          "Total cholesterol was normal. "
          + "unrelated " * 100
          + "Total ferritin was 90 ng/mL.",
          Bounds(0, 0, 1, 1),
        ),),
      ),
    ),
  )
  artifact = ArtifactWriter(chunk_bytes=2_048, overlap_bytes=0).build(document)

  with ArtifactReader(artifact) as reader:
    hit = reader.search((
      SearchQuery(("ferritin",)),
      SearchQuery(("total ferritin",)),
    ), snippet_chars=64)[0]

  assert "total ferritin" in hit.text.casefold()
  assert "total cholesterol" not in hit.text.casefold()


def test_search_combines_required_phrases_with_and_and_alternatives_with_or() -> None:
  document = DocumentContent(
    source_sha256="0" * 64,
    containers=(
      DocumentContainer(
        number=0,
        width=612,
        height=792,
        blocks=(TextBlock(
          "Total Analyte 410 ng/dL " + "first " * 60,
          Bounds(0, 0, 1, 1),
        ),),
      ),
      DocumentContainer(
        number=1,
        width=612,
        height=792,
        blocks=(TextBlock(
          "Vitamin D 32 ng/mL " + "second " * 60,
          Bounds(0, 0, 1, 1),
        ),),
      ),
      DocumentContainer(
        number=2,
        width=612,
        height=792,
        blocks=(TextBlock(
          "Follow-up Analyte 525 ng/dL " + "third " * 60,
          Bounds(0, 0, 1, 1),
        ),),
      ),
    ),
  )
  artifact = ArtifactWriter(chunk_bytes=256, overlap_bytes=0).build(document)

  with ArtifactReader(artifact) as reader:
    hits = reader.search((
      SearchQuery(("analyte", "410 ng/dL")),
      SearchQuery(("follow-up analyte", "525 ng/dL")),
    ))
    mismatched = reader.search((SearchQuery(("Vitamin D", "525 ng/dL")),))

  text = " ".join(hit.text.casefold() for hit in hits)
  assert "analyte 410 ng/dl" in text
  assert "follow-up analyte 525 ng/dl" in text
  assert mismatched == ()


def test_search_treats_boolean_syntax_as_literal_text() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    hits = reader.search((SearchQuery(('analyte" OR "vitamin',)),))

  assert hits == ()


def test_reads_consecutive_containers_without_chunk_overlap() -> None:
  artifact = ArtifactWriter(chunk_bytes=256, overlap_bytes=32).build(sample_document())

  with ArtifactReader(artifact) as reader:
    window = reader.read_containers(0, 3)

    assert "Patient summary" in window.text
    assert "Follow-up analyte" in window.text
    assert window.text.count("Analyte 410") == 1
    assert window.container_start == 0
    assert window.container_end == 2
    assert window.total_containers == 3


def test_reads_exact_container_without_neighboring_page_text() -> None:
  artifact = ArtifactWriter(chunk_bytes=256, overlap_bytes=32).build(sample_document())

  with ArtifactReader(artifact) as reader:
    window = reader.read_containers(1)

  assert window.container_start == 1
  assert window.container_end == 1
  assert "Analyte 410" in window.text
  assert "Follow-up analyte" not in window.text


def test_rejects_invalid_container_read_ranges() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    with pytest.raises(KeyError, match="unknown container"):
      reader.read_containers(3)
    with pytest.raises(ValueError, match="container count"):
      reader.read_containers(0, 0)


def test_full_text_honors_context_budget() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    assert "Vitamin D" in reader.full_text(10_000)
    with pytest.raises(ValueError, match="full-text budget"):
      reader.full_text(10)


def test_fts_stores_chunk_text_for_match_aware_snippets() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    row = reader.connection.execute("SELECT text FROM chunk_fts LIMIT 1").fetchone()
    assert "Patient summary" in row[0]
    assert reader.connection.execute("SELECT count(*) FROM chunk_fts").fetchone()[0] > 0


def test_schema_uses_source_neutral_container_names() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    assert reader.metadata().schema_version == SCHEMA_VERSION
    tables = {
      row[0]
      for row in reader.connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    assert "containers" in tables
    assert "pages" not in tables


def test_artifact_database_is_opened_immutable_and_read_only() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with (
    ArtifactReader(artifact) as reader,
    pytest.raises(sqlite3.OperationalError, match="readonly"),
  ):
    reader.connection.execute("DELETE FROM metadata")


def test_artifact_database_uses_defensive_sqlite_configuration() -> None:
  artifact = ArtifactWriter().build(sample_document())

  with ArtifactReader(artifact) as reader:
    connection = reader.connection
    assert connection.getconfig(sqlite3.SQLITE_DBCONFIG_DEFENSIVE)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_DQS_DDL)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_DQS_DML)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_TRIGGER)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_VIEW)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_TRUSTED_SCHEMA)
    assert not connection.getconfig(sqlite3.SQLITE_DBCONFIG_WRITABLE_SCHEMA)
    assert connection.execute("PRAGMA cell_size_check").fetchone()[0] == 1
    assert connection.execute("PRAGMA mmap_size").fetchone()[0] == 0
    assert connection.execute("PRAGMA query_only").fetchone()[0] == 1
    assert connection.execute("PRAGMA trusted_schema").fetchone()[0] == 0
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_ATTACHED) == 0
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_COLUMN) == 64
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_SQL_LENGTH) == 64 * 1_024
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_TRIGGER_DEPTH) == 0
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER) == 100
    assert connection.getlimit(sqlite3.SQLITE_LIMIT_WORKER_THREADS) == 0


def test_accepts_additive_artifact_schema_changes() -> None:
  artifact = ArtifactWriter().build(sample_document())
  modified = mutate_database(
    artifact,
    """
      ALTER TABLE containers ADD COLUMN future_value TEXT;
      CREATE TABLE future_data(value TEXT);
      CREATE INDEX future_containers_label ON containers(label);
      CREATE VIEW future_metadata AS SELECT key, value FROM metadata;
      CREATE TRIGGER future_metadata_insert
      AFTER INSERT ON future_data
      BEGIN
        UPDATE future_data SET value = NEW.value;
      END;
    """,
  )

  with ArtifactReader(modified) as reader:
    assert reader.search((SearchQuery(("analyte",)),))


@pytest.mark.parametrize(
  "version",
  (MIN_SUPPORTED_SCHEMA_VERSION - 1, SCHEMA_VERSION + 1),
)
def test_rejects_unsupported_artifact_schema_versions(version: int) -> None:
  artifact = ArtifactWriter().build(sample_document())
  modified = mutate_database(artifact, f"PRAGMA user_version = {version}")

  with pytest.raises(ValueError, match=f"unsupported artifact schema version: {version}"):
    ArtifactReader(modified)


def test_rejects_corrupt_artifact() -> None:
  with pytest.raises(ValueError, match="magic"):
    ArtifactReader(b"not an artifact at all")


def test_rejects_invalid_model_values() -> None:
  with pytest.raises(ValueError, match="rectangle"):
    Bounds(10, 10, 5, 20)
  with pytest.raises(ValueError, match="contiguous"):
    DocumentContent(
      source_sha256="0" * 64,
      containers=(DocumentContainer(number=1, width=10, height=10),),
    )
