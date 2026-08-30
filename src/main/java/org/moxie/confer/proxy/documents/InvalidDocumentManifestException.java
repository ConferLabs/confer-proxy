package org.moxie.confer.proxy.documents;

public class InvalidDocumentManifestException extends Exception {

  public InvalidDocumentManifestException(String message) {
    super(message);
  }

  public InvalidDocumentManifestException(String message, Throwable cause) {
    super(message, cause);
  }
}
