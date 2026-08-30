package org.moxie.confer.proxy.workers;

public class WorkerException extends Exception {

  public WorkerException(String message) {
    super(message);
  }

  public WorkerException(String message, Throwable cause) {
    super(message, cause);
  }
}
