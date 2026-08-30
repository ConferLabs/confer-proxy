package org.moxie.confer.proxy.documents;

public class UnsupportedDocumentTypeException extends Exception {

  public UnsupportedDocumentTypeException() {
    super("Document type is not supported");
  }
}
