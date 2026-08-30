package org.moxie.confer.proxy.documents.extraction;

import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.DocumentStorageWriter;
import org.moxie.confer.proxy.documents.responses.DocumentExtractionResult;
import org.moxie.confer.proxy.documents.worker.DocumentExtraction;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadDescriptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Persists one worker extraction and retains text only when it will be inlined. */
public class DocumentExtractionWriter {

  private final DocumentStorageWriter storage;
  private final DocumentObjectKeys    objectKeys;
  private final String                encryptionKey;
  private final int                   inlineTextMaxCharacters;
  private final DocumentExtraction    extraction;

  private DocumentExtractionResult result;

  public DocumentExtractionWriter(DocumentStorageWriter storage,
                                  DocumentObjectKeys    objectKeys,
                                  String                encryptionKey,
                                  int                   inlineTextMaxCharacters,
                                  DocumentExtraction    extraction)
  {
    if (inlineTextMaxCharacters < 0) {
      throw new IllegalArgumentException("Inline text limit must not be negative");
    }

    this.storage                 = Objects.requireNonNull(storage, "storage");
    this.objectKeys              = Objects.requireNonNull(objectKeys, "objectKeys");
    this.encryptionKey           = Objects.requireNonNull(encryptionKey, "encryptionKey");
    this.inlineTextMaxCharacters = inlineTextMaxCharacters;
    this.extraction              = Objects.requireNonNull(extraction, "extraction");
  }

  public DocumentExtractionResult persist() throws IOException {
    extraction.consumePayloads(this::persistPayload);
    if (result == null) {
      throw new IOException("Document extraction returned no canonical text");
    }
    return result;
  }

  private void persistPayload(DocumentWorkerPayloadDescriptor descriptor,
                              InputStream content)
    throws IOException
  {
    switch (descriptor.role()) {
      case ARTIFACT -> storage.store(objectKeys.artifact(), encryptionKey, content);
      case TEXT -> persistText(content);
      default -> throw new IOException("Document extraction returned an unexpected payload");
    }
  }

  private void persistText(InputStream content) throws IOException {
    if (inlineTextMaxCharacters > 0
        && extraction.textCharacters() <= inlineTextMaxCharacters)
    {
      byte[] text = content.readAllBytes();
      storage.store(objectKeys.text(), encryptionKey, new ByteArrayInputStream(text));
      result = new DocumentExtractionResult(
          extraction.textCharacters(),
          new String(text, StandardCharsets.UTF_8));
      return;
    }

    storage.store(objectKeys.text(), encryptionKey, content);
    result = new DocumentExtractionResult(extraction.textCharacters(), null);
  }
}
