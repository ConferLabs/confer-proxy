import zipfile
from pathlib import Path

from confer_document_worker.extraction.MediaTypes import DOCX_MEDIA_TYPE, PPTX_MEDIA_TYPE, XLSX_MEDIA_TYPE


class OfficeArchivePreflight:
  def __init__(
    self,
    max_entries: int = 20_000,
    max_expanded_bytes: int = 256 * 1024 * 1024,
    max_entry_bytes: int = 64 * 1024 * 1024,
    max_compression_ratio: int = 1_000,
  ):
    self.max_entries = max_entries
    self.max_expanded_bytes = max_expanded_bytes
    self.max_entry_bytes = max_entry_bytes
    self.max_compression_ratio = max_compression_ratio
    self.required_members = {
      DOCX_MEDIA_TYPE: "word/document.xml",
      PPTX_MEDIA_TYPE: "ppt/presentation.xml",
      XLSX_MEDIA_TYPE: "xl/workbook.xml",
    }

  def validate(self, source: Path, media_type: str) -> None:
    required_member = self.required_members.get(media_type)
    if required_member is None:
      return
    try:
      with zipfile.ZipFile(source) as archive:
        members = archive.infolist()
        if len(members) > self.max_entries:
          raise ValueError("Office document contains too many archive members")
        names = {member.filename for member in members}
        if len(names) != len(members):
          raise ValueError("Office document contains duplicate archive members")
        if required_member not in names:
          raise ValueError("Office document does not match its declared media type")
        expanded = 0
        for member in members:
          self._validate_member(member)
          expanded += member.file_size
          if expanded > self.max_expanded_bytes:
            raise ValueError("Office document expands beyond the allowed size")
    except zipfile.BadZipFile as error:
      raise ValueError("Office document archive is invalid") from error

  def _validate_member(self, member: zipfile.ZipInfo) -> None:
    path = Path(member.filename.replace("\\", "/"))
    if path.is_absolute() or ".." in path.parts:
      raise ValueError("Office document contains an unsafe archive path")
    if ((member.external_attr >> 16) & 0o170000) == 0o120000:
      raise ValueError("Office document contains a symbolic link")
    if member.flag_bits & 1:
      raise ValueError("encrypted Office documents are not supported")
    if member.file_size > self.max_entry_bytes:
      raise ValueError("Office document contains an oversized archive member")
    ratio = member.file_size / max(1, member.compress_size)
    if ratio > self.max_compression_ratio:
      raise ValueError("Office document contains an excessive compression ratio")
