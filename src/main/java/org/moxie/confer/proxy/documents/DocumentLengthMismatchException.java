package org.moxie.confer.proxy.documents;

import java.io.IOException;

public class DocumentLengthMismatchException extends IOException {

  public DocumentLengthMismatchException() {
    super("Stored document length does not match");
  }
}
