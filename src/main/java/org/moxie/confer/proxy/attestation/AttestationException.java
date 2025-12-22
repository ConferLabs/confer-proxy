package org.moxie.confer.proxy.attestation;

/**
 * Exception thrown when attestation generation or validation fails.
 */
public class AttestationException extends Exception {
  public AttestationException(String message) {
    super(message);
  }

  public AttestationException(String message, Throwable cause) {
    super(message, cause);
  }

  public AttestationException(Throwable cause) {
    super(cause);
  }
}
