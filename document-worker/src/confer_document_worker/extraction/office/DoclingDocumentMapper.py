import io
from pathlib import Path
from typing import Any

from confer_document_worker.domain.Bounds import Bounds
from confer_document_worker.domain.ContainerKind import ContainerKind
from confer_document_worker.domain.DocumentContainer import DocumentContainer
from confer_document_worker.domain.DocumentContent import DocumentContent
from confer_document_worker.domain.DocumentSource import DocumentSource
from confer_document_worker.domain.OutlineEntry import OutlineEntry
from confer_document_worker.domain.TextBlock import TextBlock
from confer_document_worker.domain.VisualRegion import VisualRegion
from confer_document_worker.domain.VisualRegionType import VisualRegionType
from confer_document_worker.extraction.MediaTypes import (
  DOCX_MEDIA_TYPE,
  PPTX_MEDIA_TYPE,
  XLSX_MEDIA_TYPE,
)
from confer_document_worker.extraction.office.ContainerDraft import ContainerDraft
from confer_document_worker.extraction.office.OfficeExtractionLimits import OfficeExtractionLimits


class DoclingDocumentMapper:
  def __init__(self, limits: OfficeExtractionLimits):
    self.limits = limits

  def map(
    self,
    document: Any,
    source: DocumentSource,
    filename: str,
    media_type: str,
  ) -> DocumentContent:
    from docling_core.types.doc import PictureItem, SectionHeaderItem, TableItem, TextItem, TitleItem

    kind = self._kind(media_type)
    page_items = document.pages
    if len(page_items) > self.limits.containers:
      raise ValueError("document contains too many containers")
    labels = self._container_labels(document, kind)
    drafts: dict[int, ContainerDraft] = {}
    for page_number, page in page_items.items():
      drafts[page_number - 1] = ContainerDraft(
        page_number - 1,
        max(1.0, float(page.size.width)),
        max(1.0, float(page.size.height)),
        kind,
        labels.get(page_number - 1),
      )
    if not drafts:
      drafts[0] = ContainerDraft(0, 1, 1, kind, labels.get(0, filename))

    outline: list[OutlineEntry] = []
    region_counts: dict[tuple[int, str], int] = {}
    total_image_bytes = 0
    for item, _level in document.iterate_items():
      page_number, bounds = self._location(item, drafts)
      if page_number >= self.limits.containers:
        raise ValueError("document item references an invalid container")
      draft = drafts.setdefault(page_number, ContainerDraft(page_number, 1, 1, kind))
      if bounds is None:
        y = len(draft.blocks)
        bounds = Bounds(0, y, 1, y + 1)
        draft.height = max(draft.height, y + 1)

      if isinstance(item, TableItem):
        text = item.export_to_markdown(document).strip()
        if text:
          draft.blocks.append(TextBlock(text, bounds))
          draft.regions.append(self._region(
            region_counts,
            page_number,
            VisualRegionType.TABLE,
            bounds,
            renderable=False,
          ))
        continue

      if isinstance(item, PictureItem):
        caption = item.caption_text(document).strip()
        text = f"[Image: {caption}]" if caption else "[Image]"
        draft.blocks.append(TextBlock(text, bounds))
        asset = self._picture_png(
          item,
          document,
          self.limits.total_image_bytes - total_image_bytes,
        )
        if asset is not None:
          total_image_bytes += len(asset)
        region_type = VisualRegionType.CHART if item.label.value == "chart" else VisualRegionType.IMAGE
        draft.regions.append(self._region(
          region_counts,
          page_number,
          region_type,
          bounds,
          renderable=asset is not None,
          asset=asset,
        ))
        continue

      if isinstance(item, TextItem):
        text = item.text.strip()
        if not text:
          continue
        if isinstance(item, (SectionHeaderItem, TitleItem)):
          level = item.level if isinstance(item, SectionHeaderItem) else 1
          outline.append(OutlineEntry(level, text, page_number))
          text = f"{'#' * min(6, level)} {text}"
        draft.blocks.append(TextBlock(text, bounds))

    maximum_container = max(drafts)
    for index in range(maximum_container + 1):
      drafts.setdefault(index, ContainerDraft(index, 1, 1, kind))
    containers = tuple(
      DocumentContainer(
        number=index,
        width=drafts[index].width,
        height=drafts[index].height,
        blocks=tuple(drafts[index].blocks),
        regions=tuple(drafts[index].regions),
        kind=drafts[index].kind,
        label=drafts[index].label,
      )
      for index in range(maximum_container + 1)
    )
    return DocumentContent(
      source_sha256=source.sha256,
      containers=containers,
      media_type=media_type,
      title=Path(filename).stem or getattr(document, "name", None),
      outline=tuple(outline),
    )

  def _kind(self, media_type: str) -> ContainerKind:
    if media_type == PPTX_MEDIA_TYPE:
      return ContainerKind.SLIDE
    if media_type == XLSX_MEDIA_TYPE:
      return ContainerKind.SHEET
    if media_type == DOCX_MEDIA_TYPE:
      return ContainerKind.DOCUMENT
    return ContainerKind.TEXT

  def _container_labels(self, document: Any, kind: ContainerKind) -> dict[int, str]:
    if kind != ContainerKind.SHEET:
      return {}
    from docling_core.types.doc import GroupLabel

    names = [
      group.name
      for group in document.groups
      if group.label == GroupLabel.SHEET and group.name
    ]
    return {index: name for index, name in enumerate(names)}

  def _location(
    self,
    item: Any,
    drafts: dict[int, ContainerDraft],
  ) -> tuple[int, Bounds | None]:
    provenance = getattr(item, "prov", ())
    if not provenance:
      return 0, None
    value = provenance[0]
    page_number = max(0, int(value.page_no) - 1)
    draft = drafts.get(page_number)
    page_height = draft.height if draft is not None else max(
      1.0,
      float(value.bbox.t),
      float(value.bbox.b),
    )
    bbox = value.bbox.to_top_left_origin(page_height)
    x0 = max(0.0, min(float(bbox.l), float(bbox.r)))
    x1 = max(x0 + 0.001, max(float(bbox.l), float(bbox.r)))
    y0 = max(0.0, min(float(bbox.t), float(bbox.b)))
    y1 = max(y0 + 0.001, max(float(bbox.t), float(bbox.b)))
    return page_number, Bounds(x0, y0, x1, y1)

  def _region(
    self,
    counts: dict[tuple[int, str], int],
    page_number: int,
    region_type: VisualRegionType,
    bounds: Bounds,
    renderable: bool,
    asset: bytes | None = None,
  ) -> VisualRegion:
    key = (page_number, region_type.value)
    counts[key] = counts.get(key, 0) + 1
    return VisualRegion(
      f"c{page_number + 1}-{region_type.value}-{counts[key]}",
      region_type,
      bounds,
      renderable=renderable,
      asset_media_type="image/png" if asset is not None else None,
      asset=asset,
    )

  def _picture_png(
    self,
    item: Any,
    document: Any,
    remaining_bytes: int,
  ) -> bytes | None:
    if remaining_bytes <= 0:
      return None
    image = item.get_image(document)
    if image is None:
      return None
    image = image.copy()
    pixels = image.width * image.height
    if pixels > self.limits.image_pixels:
      scale = (self.limits.image_pixels / pixels) ** 0.5
      image.thumbnail((
        max(1, int(image.width * scale)),
        max(1, int(image.height * scale)),
      ))
    output = io.BytesIO()
    image.convert("RGB").save(output, format="PNG", optimize=True)
    png = output.getvalue()
    if len(png) > self.limits.image_bytes or len(png) > remaining_bytes:
      return None
    return png
