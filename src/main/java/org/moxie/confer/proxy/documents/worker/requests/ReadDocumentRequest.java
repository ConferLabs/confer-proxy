package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

public record ReadDocumentRequest(@JsonProperty("document_id") String documentId,
                                  @JsonProperty("container_number") int containerNumber,
                                  @JsonProperty("container_count") int containerCount)
  implements DocumentWorkerRequest
{

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (containerNumber < 0) {
      throw new DocumentWorkerProtocolException("Document container number cannot be negative");
    }
    if (containerCount < 1 || containerCount > 20) {
      throw new DocumentWorkerProtocolException("Document container count is outside the allowed range");
    }
  }

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.READ;
  }
}
