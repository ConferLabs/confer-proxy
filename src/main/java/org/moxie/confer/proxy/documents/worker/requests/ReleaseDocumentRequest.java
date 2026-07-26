package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;

public record ReleaseDocumentRequest(@JsonProperty("document_id") String documentId)
  implements DocumentWorkerRequest
{

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.RELEASE;
  }
}
