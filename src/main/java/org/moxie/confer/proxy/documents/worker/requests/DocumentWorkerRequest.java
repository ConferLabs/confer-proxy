package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public interface DocumentWorkerRequest {

  int VERSION = 1;

  @JsonIgnore
  DocumentWorkerOperation operation();

  @JsonIgnore
  default List<DocumentWorkerRequestPayload> payloads() {
    return List.of();
  }

  default void validate() throws DocumentWorkerProtocolException {
  }
}
