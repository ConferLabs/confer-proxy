package org.moxie.confer.proxy.attachments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentDescriptor;
import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.DocumentStorageWriter;
import org.moxie.confer.proxy.documents.extraction.StoredDocumentExtractor;
import org.moxie.confer.proxy.documents.responses.DocumentExtractionResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoredAttachmentPublisherTest {

  private static final String ATTACHMENT_PREFIX = "generated-attachments";

  @Mock
  private StoredDocumentExtractor extractor;

  private RecordingStorage          storage;
  private StoredAttachmentPublisher publisher;

  @BeforeEach
  void setUp() {
    storage = new RecordingStorage();
    publisher = new StoredAttachmentPublisher(storage, extractor);
  }

  @Test
  void storesAndExtractsASupportedAttachment() throws Exception {
    byte[] content = new byte[] {1, 2, 3};
    when(extractor.extract(
        any(DocumentDescriptor.class),
        any(DocumentObjectKeys.class),
        anyString(),
        anyInt())).thenAnswer(invocation -> {
          assertTrue(storage.stored);
          return new DocumentExtractionResult(123, null);
        });

    AttachmentReference reference = publisher.publish(
        ATTACHMENT_PREFIX,
        "report.pdf",
        new ByteArrayInputStream(content));

    assertEquals(ATTACHMENT_PREFIX + "/" + reference.id(), storage.objectKey);
    assertEquals(reference.sourceObjectKey(), storage.objectKey);
    assertEquals(reference.encryptionKey(), storage.encryptionKey);
    assertEquals(AttachmentPublisher.MAX_BYTES, storage.maximumBytes);
    assertArrayEquals(content, storage.content);
    assertEquals("report.pdf", reference.filename());
    assertEquals("application/pdf", reference.contentType());
    assertEquals(content.length, reference.size());
    assertEquals(123, reference.extractedTextLength());
    verify(extractor).extract(
        argThat(document ->
            document.filename().equals(reference.filename())
                && document.mediaType().equals(reference.contentType())
                && document.totalLength() == reference.size()),
        argThat(objectKeys -> objectKeys.source().equals(reference.sourceObjectKey())),
        eq(reference.encryptionKey()),
        eq(0));
  }

  @Test
  void storesAnUnsupportedFileWithoutExtraction() throws Exception {
    AttachmentReference reference = publisher.publish(
        ATTACHMENT_PREFIX,
        "empty.bin",
        new ByteArrayInputStream(new byte[0]));

    assertArrayEquals(new byte[0], storage.content);
    assertEquals("application/octet-stream", reference.contentType());
    assertNull(reference.extractedTextLength());
    verifyNoInteractions(extractor);
  }

  @Test
  void recognizesHeicAndHeifImages() throws Exception {
    AttachmentReference heic = publisher.publish(
        ATTACHMENT_PREFIX,
        "photo.HEIC",
        new ByteArrayInputStream(new byte[] {1}));
    AttachmentReference heif = publisher.publish(
        ATTACHMENT_PREFIX,
        "photo.heif",
        new ByteArrayInputStream(new byte[] {1}));

    assertEquals("image/heic", heic.contentType());
    assertEquals("image/heif", heif.contentType());
    verifyNoInteractions(extractor);
  }

  @Test
  void preservesZeroLengthExtraction() throws Exception {
    when(extractor.extract(
        any(DocumentDescriptor.class),
        any(DocumentObjectKeys.class),
        anyString(),
        anyInt())).thenReturn(new DocumentExtractionResult(0, null));

    AttachmentReference reference = publisher.publish(
        ATTACHMENT_PREFIX,
        "blank.pdf",
        new ByteArrayInputStream(new byte[] {1}));

    assertEquals(Long.valueOf(0), reference.extractedTextLength());
  }

  @Test
  void keepsTheStoredAttachmentWhenExtractionFails() throws Exception {
    when(extractor.extract(
        any(DocumentDescriptor.class),
        any(DocumentObjectKeys.class),
        anyString(),
        anyInt())).thenThrow(new IOException("extraction failed"));

    AttachmentReference reference = publisher.publish(
        ATTACHMENT_PREFIX,
        "report.docx",
        new ByteArrayInputStream(new byte[] {1}));

    assertNull(reference.extractedTextLength());
    assertTrue(storage.stored);
  }

  private static class RecordingStorage implements DocumentStorageWriter {

    private String  objectKey;
    private String  encryptionKey;
    private byte[]  content;
    private long    maximumBytes;
    private boolean stored;

    @Override
    public long store(String objectKey,
                      String encryptionKey,
                      InputStream content,
                      long maximumBytes)
      throws IOException
    {
      this.objectKey     = objectKey;
      this.encryptionKey = encryptionKey;
      this.content       = content.readAllBytes();
      this.maximumBytes  = maximumBytes;
      stored             = true;
      return this.content.length;
    }
  }
}
