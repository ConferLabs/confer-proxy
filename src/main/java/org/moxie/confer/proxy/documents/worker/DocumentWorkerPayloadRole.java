package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DocumentWorkerPayloadRole {

  SOURCE("source", null),
  ARTIFACT("artifact", "application/vnd.confer.document-artifact"),
  TEXT("text", "text/plain; charset=utf-8"),
  RESULT("result", "application/json"),
  IMAGE("image", "image/png");

  private final String wireValue;
  private final String mediaType;

  DocumentWorkerPayloadRole(String wireValue, String mediaType) {
    this.wireValue = wireValue;
    this.mediaType = mediaType;
  }

  @JsonCreator
  public static DocumentWorkerPayloadRole fromWireValue(String value)
    throws DocumentWorkerProtocolException
  {
    for (DocumentWorkerPayloadRole role : values()) {
      if (role.wireValue.equals(value)) {
        return role;
      }
    }

    throw new DocumentWorkerProtocolException("Unknown document worker payload role");
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  public String requiredMediaType() {
    if (mediaType == null) {
      throw new IllegalStateException("Source payloads require an explicit media type");
    }

    return mediaType;
  }

  public boolean acceptsMediaType(String value) {
    return value != null && !value.isBlank() && (mediaType == null || mediaType.equals(value));
  }
}
