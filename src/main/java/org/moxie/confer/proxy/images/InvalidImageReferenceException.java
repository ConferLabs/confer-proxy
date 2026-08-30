package org.moxie.confer.proxy.images;

public class InvalidImageReferenceException extends Exception {

  public InvalidImageReferenceException(String message) {
    super(message);
  }

  public InvalidImageReferenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
