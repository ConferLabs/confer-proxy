package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;

public class DocumentWorkerProtocolException extends IOException {

  public DocumentWorkerProtocolException(String message) {
    super(message);
  }

  public DocumentWorkerProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
