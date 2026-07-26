package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public record ExtractDocumentRequest(String filename,
                                     @JsonProperty("content_type") String contentType,
                                     @JsonIgnore DocumentWorkerRequestPayload source)
  implements DocumentWorkerRequest
{

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.EXTRACT;
  }

  @Override
  public List<DocumentWorkerRequestPayload> payloads() {
    return List.of(source);
  }

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (source == null) {
      throw new DocumentWorkerProtocolException("Document worker request payload is missing");
    }
    source.requireRole(DocumentWorkerPayloadRole.SOURCE);
    source.requireMediaType(contentType);
  }
}
