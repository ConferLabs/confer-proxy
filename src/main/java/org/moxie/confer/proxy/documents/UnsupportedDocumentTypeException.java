package org.moxie.confer.proxy.documents;

public final class UnsupportedDocumentTypeException extends Exception {

  public UnsupportedDocumentTypeException() {
    super("Document type is not supported");
  }
}
