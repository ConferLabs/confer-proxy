from __future__ import annotations

import sqlite3
from contextlib import ExitStack
from pathlib import Path

from confer_document_worker.artifact.ArtifactFormat import (
  APPLICATION_ID,
  MAX_DATABASE_BYTES,
  MIN_SUPPORTED_SCHEMA_VERSION,
  SCHEMA_VERSION,
)


class ArtifactDatabase:
  def __init__(self, path: Path):
    database_uri = path.resolve().as_uri() + "?mode=ro&immutable=1"
    with ExitStack() as resources:
      try:
        self.connection = sqlite3.connect(database_uri, uri=True)
        resources.callback(self.connection.close)
        self.connection.row_factory = sqlite3.Row
        self._configure()
        self._validate_format()
        self.text_bytes = self._text_bytes()
      except sqlite3.DatabaseError as error:
        raise ValueError("artifact database is invalid") from error
      self._resources = resources.pop_all()

  def close(self) -> None:
    self._resources.close()

  def __enter__(self) -> ArtifactDatabase:
    return self

  def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
    self.close()

  def _configure(self) -> None:
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_DEFENSIVE, True)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_DQS_DDL, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_DQS_DML, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_TRUSTED_SCHEMA, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_TRIGGER, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_ENABLE_VIEW, False)
    self.connection.setconfig(sqlite3.SQLITE_DBCONFIG_WRITABLE_SCHEMA, False)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_ATTACHED, 0)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_COLUMN, 64)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_COMPOUND_SELECT, 10)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_EXPR_DEPTH, 100)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_FUNCTION_ARG, 32)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MAX_DATABASE_BYTES)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_LIKE_PATTERN_LENGTH, 4_096)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_SQL_LENGTH, 64 * 1_024)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_TRIGGER_DEPTH, 0)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER, 100)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_VDBE_OP, 100_000)
    self.connection.setlimit(sqlite3.SQLITE_LIMIT_WORKER_THREADS, 0)
    self.connection.execute("PRAGMA cell_size_check = ON")
    self.connection.execute("PRAGMA mmap_size = 0")
    self.connection.execute("PRAGMA query_only = ON")
    self.connection.execute("PRAGMA trusted_schema = OFF")

  def _validate_format(self) -> None:
    application_id = self.connection.execute("PRAGMA application_id").fetchone()[0]
    if application_id != APPLICATION_ID:
      raise ValueError("artifact database application ID is invalid")
    version = self.connection.execute("PRAGMA user_version").fetchone()[0]
    if not MIN_SUPPORTED_SCHEMA_VERSION <= version <= SCHEMA_VERSION:
      raise ValueError(f"unsupported artifact schema version: {version}")

  def _text_bytes(self) -> int:
    row = self.connection.execute(
      "SELECT length(text_utf8) FROM document_text WHERE id = 1",
    ).fetchone()
    if row is None:
      raise ValueError("artifact canonical text is missing")
    return int(row[0])
