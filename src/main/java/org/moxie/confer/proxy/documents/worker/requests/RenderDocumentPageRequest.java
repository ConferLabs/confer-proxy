package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public record RenderDocumentPageRequest(@JsonProperty("document_id") String documentId,
                                        @JsonProperty("container_number") int containerNumber,
                                        int dpi,
                                        @JsonIgnore DocumentWorkerRequestPayload source)
  implements DocumentWorkerRequest
{

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.RENDER;
  }

  @Override
  public List<DocumentWorkerRequestPayload> payloads() {
    return List.of(source);
  }

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (containerNumber < 0) {
      throw new DocumentWorkerProtocolException("Document container number cannot be negative");
    }
    if (source == null) {
      throw new DocumentWorkerProtocolException("Document worker source payload is missing");
    }
    source.requireRole(DocumentWorkerPayloadRole.SOURCE);
    source.requireMediaType("application/pdf");
  }
}
