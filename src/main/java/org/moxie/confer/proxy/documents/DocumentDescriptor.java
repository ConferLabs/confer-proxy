package org.moxie.confer.proxy.documents;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DocumentDescriptor {

  private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.ofEntries(
      Map.entry(".pdf", "application/pdf"),
      Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry(".html", "text/html"),
      Map.entry(".htm", "text/html"),
      Map.entry(".md", "text/markdown"),
      Map.entry(".txt", "text/plain"),
      Map.entry(".csv", "text/csv"),
      Map.entry(".json", "application/json"),
      Map.entry(".xml", "application/xml"),
      Map.entry(".yaml", "application/x-yaml"),
      Map.entry(".yml", "application/x-yaml"));

  private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
      "application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "text/html",
      "text/markdown",
      "text/x-markdown",
      "text/plain",
      "text/css",
      "text/csv",
      "text/xml",
      "application/xml",
      "application/json",
      "text/javascript",
      "application/javascript",
      "application/x-yaml",
      "text/yaml");

  private final String filename;
  private final String mediaType;
  private final long   totalLength;

  public DocumentDescriptor(String filename,
                            String contentType,
                            long totalLength)
    throws UnsupportedDocumentTypeException
  {
    this.filename    = filename;
    this.mediaType   = resolveMediaType(filename, contentType);
    this.totalLength = totalLength;
  }

  public String filename() {
    return filename;
  }

  public String mediaType() {
    return mediaType;
  }

  public long totalLength() {
    return totalLength;
  }

  private static String resolveMediaType(String filename,
                                         String contentType)
    throws UnsupportedDocumentTypeException
  {
    String mediaType = contentType == null
        ? ""
        : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (mediaType.isEmpty() || "application/octet-stream".equals(mediaType)) {
      String normalizedFilename = filename.toLowerCase(Locale.ROOT);
      mediaType = MEDIA_TYPES_BY_EXTENSION.entrySet().stream()
          .filter(entry -> normalizedFilename.endsWith(entry.getKey()))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse("");
    }
    if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
      throw new UnsupportedDocumentTypeException();
    }
    return mediaType;
  }
}
