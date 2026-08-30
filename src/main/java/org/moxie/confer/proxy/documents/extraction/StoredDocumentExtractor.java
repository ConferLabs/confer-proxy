package org.moxie.confer.proxy.documents.extraction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.DocumentDescriptor;
import org.moxie.confer.proxy.documents.DocumentLengthMismatchException;
import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.documents.DocumentStorageWriter;
import org.moxie.confer.proxy.documents.responses.DocumentExtractionResult;
import org.moxie.confer.proxy.documents.worker.DocumentExtraction;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;

import java.io.IOException;

@ApplicationScoped
public class StoredDocumentExtractor {

  private final DocumentStorageGateway  storage;
  private final DocumentStorageWriter   storageWriter;
  private final DocumentWorkerScheduler workers;

  @Inject
  public StoredDocumentExtractor(DocumentStorageGateway  storage,
                                 DocumentStorageWriter   storageWriter,
                                 DocumentWorkerScheduler workers)
  {
    this.storage       = storage;
    this.storageWriter = storageWriter;
    this.workers       = workers;
  }

  public DocumentExtractionResult extract(DocumentDescriptor document,
                                          DocumentObjectKeys objectKeys,
                                          String encryptionKey,
                                          int inlineTextMaxCharacters)
      throws IOException
  {
    try (DocumentExtraction extraction = workers.extract(connection -> {
      try (DecryptedDocument source = storage.open(
          objectKeys.source(),
          encryptionKey))
      {
        if (source.length() != document.totalLength()) {
          throw new DocumentLengthMismatchException();
        }

        DocumentWorkerRequestPayload sourcePayload = new DocumentWorkerRequestPayload(
            DocumentWorkerPayloadRole.SOURCE,
            document.mediaType(),
            source.length(),
            source.content());

        return connection.extract(new ExtractDocumentRequest(
            document.filename(),
            document.mediaType(),
            sourcePayload));
      }
    })) {
      return new DocumentExtractionWriter(
          storageWriter,
          objectKeys,
          encryptionKey,
          inlineTextMaxCharacters,
          extraction).persist();
    }
  }
}
