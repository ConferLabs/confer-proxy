package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public record OpenDocumentRequest(@JsonProperty("document_id") String documentId,
                                  @JsonIgnore DocumentWorkerRequestPayload artifact)
  implements DocumentWorkerRequest
{

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.OPEN;
  }

  @Override
  public List<DocumentWorkerRequestPayload> payloads() {
    return List.of(artifact);
  }

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (artifact == null) {
      throw new DocumentWorkerProtocolException("Document worker artifact payload is missing");
    }
    artifact.requireRole(DocumentWorkerPayloadRole.ARTIFACT);
  }
}
