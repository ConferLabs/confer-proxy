package org.moxie.confer.proxy.images;

public final class InvalidImageReferenceException extends Exception {

  public InvalidImageReferenceException(String message) {
    super(message);
  }

  public InvalidImageReferenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
