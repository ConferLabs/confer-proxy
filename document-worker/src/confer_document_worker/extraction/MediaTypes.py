DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
PPTX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
HTML_MEDIA_TYPE = "text/html"
MARKDOWN_MEDIA_TYPES = frozenset({"text/markdown", "text/x-markdown"})
PLAIN_MEDIA_TYPES = frozenset({
  "text/plain",
  "text/css",
  "text/csv",
  "text/xml",
  "application/xml",
  "application/json",
  "text/javascript",
  "application/javascript",
  "application/x-yaml",
  "text/yaml",
})
STRUCTURED_MEDIA_TYPES = frozenset({
  DOCX_MEDIA_TYPE,
  PPTX_MEDIA_TYPE,
  XLSX_MEDIA_TYPE,
  HTML_MEDIA_TYPE,
  *MARKDOWN_MEDIA_TYPES,
})
SUPPORTED_MEDIA_TYPES = frozenset({
  "application/pdf",
  *STRUCTURED_MEDIA_TYPES,
  *PLAIN_MEDIA_TYPES,
})
