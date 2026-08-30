package org.moxie.confer.proxy.attachments;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.DocumentDescriptor;
import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.DocumentStorageWriter;
import org.moxie.confer.proxy.documents.UnsupportedDocumentTypeException;
import org.moxie.confer.proxy.documents.extraction.StoredDocumentExtractor;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class StoredAttachmentPublisher implements AttachmentPublisher {

  private static final Logger log = LoggerFactory.getLogger(StoredAttachmentPublisher.class);

  private final DocumentStorageWriter    storage;
  private final StoredDocumentExtractor  extractor;
  private final SecureRandom             random;

  @Inject
  public StoredAttachmentPublisher(DocumentStorageWriter   storage,
                                   StoredDocumentExtractor extractor)
  {
    this.storage   = storage;
    this.extractor = extractor;
    this.random    = new SecureRandom();
  }

  @Override
  public AttachmentReference publish(String      attachmentPrefix,
                                     String      filename,
                                     InputStream content)
      throws IOException
  {
    byte[] key = new byte[32];
    random.nextBytes(key);

    String             encryptionKey = Base64.getEncoder().encodeToString(key);
    String             id            = UUID.randomUUID().toString();
    DocumentObjectKeys objectKeys    = getObjectKeys(attachmentPrefix + "/" + id);

    long size = storage.store(objectKeys.source(), encryptionKey, content, AttachmentPublisher.MAX_BYTES);

    Optional<DocumentDescriptor> document    = getDocument(filename, size);
    String                       contentType = document.map(DocumentDescriptor::mediaType).orElseGet(() -> getContentType(filename));

    Long extractedTextLength = document.isPresent()
        ? extract(document.orElseThrow(), objectKeys, encryptionKey)
        : null;

    return new AttachmentReference(
        id,
        filename,
        contentType,
        size,
        encryptionKey,
        objectKeys.source(),
        extractedTextLength);
  }

  private Long extract(DocumentDescriptor document,
                       DocumentObjectKeys objectKeys,
                       String encryptionKey)
  {
    try {
      return extractor.extract(document, objectKeys, encryptionKey, 0).textLength();
    } catch (IOException error) {
      log.warn("Generated attachment extraction failed");
      return null;
    }
  }

  private static Optional<DocumentDescriptor> getDocument(String filename,
                                                          long size)
  {
    try {
      return Optional.of(new DocumentDescriptor(filename, null, size));
    } catch (UnsupportedDocumentTypeException error) {
      return Optional.empty();
    }
  }

  private static String getContentType(String filename) {
    String normalizedFilename = filename.toLowerCase(Locale.ROOT);

    if (normalizedFilename.endsWith(".heic")) {
      return "image/heic";
    }

    if (normalizedFilename.endsWith(".heif")) {
      return "image/heif";
    }

    String contentType = URLConnection.guessContentTypeFromName(filename);

    return contentType == null
        ? "application/octet-stream"
        : contentType;
  }

  private static DocumentObjectKeys getObjectKeys(String sourceObjectKey)
      throws IOException
  {
    try {
      return new DocumentObjectKeys(sourceObjectKey);
    } catch (InvalidObjectStorageKeyException error) {
      throw new IOException("Attachment object key is invalid", error);
    }
  }
}
