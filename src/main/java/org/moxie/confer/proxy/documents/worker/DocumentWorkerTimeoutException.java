package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;

public class DocumentWorkerTimeoutException extends IOException {

  public DocumentWorkerTimeoutException() {
    super("Timed out waiting for a document worker");
  }
}
