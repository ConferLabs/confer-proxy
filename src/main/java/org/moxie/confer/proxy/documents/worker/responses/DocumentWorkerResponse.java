package org.moxie.confer.proxy.documents.worker.responses;

import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DocumentWorkerResponse<T>(
    DocumentWorkerResponseStatus status,
    T result,
    DocumentWorkerError error,
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads)
{

  public DocumentWorkerResponse {
    payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
  }

  public boolean successful() {
    return status == DocumentWorkerResponseStatus.OK;
  }

  public static <T> DocumentWorkerResponse<T> success(
      T result,
      Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads)
  {
    return new DocumentWorkerResponse<>(
        DocumentWorkerResponseStatus.OK,
        result,
        null,
        payloads);
  }

  public static DocumentWorkerResponse<Void> success(
      Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads)
  {
    return success(null, payloads);
  }

  public static DocumentWorkerResponse<Void> failure(String code, String message) {
    return new DocumentWorkerResponse<>(
        DocumentWorkerResponseStatus.ERROR,
        null,
        new DocumentWorkerError(code, message),
        Map.of());
  }

  public T requiredResult() throws DocumentWorkerProtocolException {
    if (result == null) {
      throw new DocumentWorkerProtocolException("Document worker response result is missing");
    }
    return result;
  }

  public DocumentWorkerResponsePayload requiredPayload(DocumentWorkerPayloadRole role)
    throws IOException
  {
    DocumentWorkerResponsePayload payload = payloads.get(role);
    if (payload == null) {
      throw new IOException("Document worker response is incomplete");
    }
    return payload;
  }
}
