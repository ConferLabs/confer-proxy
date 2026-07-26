package org.moxie.confer.proxy.documents.worker.requests;

import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.io.InputStream;

public record DocumentWorkerRequestPayload(DocumentWorkerPayloadRole role,
                                           String mediaType,
                                           long length,
                                           InputStream input)
{

  public void validate() throws DocumentWorkerProtocolException {
    if (role == null || mediaType == null || input == null) {
      throw new DocumentWorkerProtocolException("Document worker request payload is incomplete");
    }
    if (length < 0) {
      throw new DocumentWorkerProtocolException(
          "Document worker payload length cannot be negative");
    }
  }

  void requireRole(DocumentWorkerPayloadRole expected) throws DocumentWorkerProtocolException {
    validate();
    if (role != expected) {
      throw new DocumentWorkerProtocolException(
          "Document worker request payload has the wrong role");
    }
  }

  void requireMediaType(String expected) throws DocumentWorkerProtocolException {
    validate();
    if (!mediaType.equals(expected)) {
      throw new DocumentWorkerProtocolException(
          "Document worker request payload has the wrong media type");
    }
  }
}
