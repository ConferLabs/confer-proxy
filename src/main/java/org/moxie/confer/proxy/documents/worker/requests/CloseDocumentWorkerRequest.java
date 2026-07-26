package org.moxie.confer.proxy.documents.worker.requests;

import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;

public record CloseDocumentWorkerRequest() implements DocumentWorkerRequest {

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.CLOSE;
  }
}
