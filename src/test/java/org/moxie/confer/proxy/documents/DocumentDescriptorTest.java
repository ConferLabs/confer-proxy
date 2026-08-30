package org.moxie.confer.proxy.documents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentDescriptorTest {

  @Test
  void preservesValidMetadata() throws UnsupportedDocumentTypeException {
    DocumentDescriptor document = new DocumentDescriptor(
        "report.pdf",
        "application/pdf",
        1024L);

    assertEquals("report.pdf", document.filename());
    assertEquals("application/pdf", document.mediaType());
    assertEquals(1024, document.totalLength());
  }

  @Test
  void normalizesMediaType() throws UnsupportedDocumentTypeException {
    DocumentDescriptor document = new DocumentDescriptor(
        "report.pdf",
        " Application/PDF; charset=binary ",
        1L);

    assertEquals("application/pdf", document.mediaType());
  }

  @Test
  void infersMediaTypeWhenContentTypeIsMissing() throws UnsupportedDocumentTypeException {
    DocumentDescriptor document = new DocumentDescriptor("REPORT.PDF", null, 1L);

    assertEquals("application/pdf", document.mediaType());
  }

  @Test
  void infersMediaTypeForGenericBinaryContent() throws UnsupportedDocumentTypeException {
    DocumentDescriptor document = new DocumentDescriptor(
        "report.docx",
        "application/octet-stream",
        1L);

    assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        document.mediaType());
  }

  @Test
  void infersGeneratedTextMediaTypesFromFilename() throws UnsupportedDocumentTypeException {
    assertEquals(
        "text/css",
        new DocumentDescriptor("styles.css", null, 1L).mediaType());
    assertEquals(
        "text/javascript",
        new DocumentDescriptor("script.js", null, 1L).mediaType());
  }

  @Test
  void rejectsUnsupportedContentType() {
    assertThrows(
        UnsupportedDocumentTypeException.class,
        () -> new DocumentDescriptor("report.doc", "application/msword", 1L));
  }

  @Test
  void rejectsUnknownExtensionWhenContentTypeIsMissing() {
    assertThrows(
        UnsupportedDocumentTypeException.class,
        () -> new DocumentDescriptor("report.bin", null, 1L));
  }
}
