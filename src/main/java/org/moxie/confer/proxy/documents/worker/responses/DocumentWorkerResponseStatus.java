package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

public enum DocumentWorkerResponseStatus {

  OK("ok"),
  ERROR("error");

  private final String wireValue;

  DocumentWorkerResponseStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonCreator
  public static DocumentWorkerResponseStatus fromWireValue(String value)
    throws DocumentWorkerProtocolException
  {
    for (DocumentWorkerResponseStatus status : values()) {
      if (status.wireValue.equals(value)) {
        return status;
      }
    }

    throw new DocumentWorkerProtocolException("Unknown document worker response status");
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }
}
