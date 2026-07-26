package org.moxie.confer.proxy.documents;

import java.io.IOException;

public class DocumentNotFoundException extends IOException {

  public DocumentNotFoundException() {
    super("Document object was not found");
  }
}
