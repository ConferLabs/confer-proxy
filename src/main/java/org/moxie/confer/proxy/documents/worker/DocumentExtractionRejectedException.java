package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;

public final class DocumentExtractionRejectedException extends IOException {

  public DocumentExtractionRejectedException() {
    super("Document worker rejected the extraction request");
  }
}
