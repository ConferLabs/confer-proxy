package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public record OpenTextDocumentRequest(@JsonProperty("document_id") String documentId,
                                      String filename,
                                      @JsonIgnore DocumentWorkerRequestPayload text)
  implements DocumentWorkerRequest
{

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.OPEN_TEXT;
  }

  @Override
  public List<DocumentWorkerRequestPayload> payloads() {
    return List.of(text);
  }

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (text == null) {
      throw new DocumentWorkerProtocolException("Document worker text payload is missing");
    }
    text.requireRole(DocumentWorkerPayloadRole.TEXT);
  }
}
