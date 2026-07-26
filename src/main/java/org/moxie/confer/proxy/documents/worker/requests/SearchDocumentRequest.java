package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOperation;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerProtocolException;

import java.util.List;

public record SearchDocumentRequest(@JsonProperty("document_id") String documentId,
                                    List<DocumentSearchQuery> queries,
                                    int limit,
                                    @JsonProperty("snippet_characters") int snippetCharacters)
  implements DocumentWorkerRequest
{

  private static final int MIN_SNIPPET_CHARACTERS = 64;
  private static final int MAX_SNIPPET_CHARACTERS = 4_000;

  @Override
  public DocumentWorkerOperation operation() {
    return DocumentWorkerOperation.SEARCH;
  }

  @Override
  public void validate() throws DocumentWorkerProtocolException {
    if (snippetCharacters < MIN_SNIPPET_CHARACTERS
        || snippetCharacters > MAX_SNIPPET_CHARACTERS)
    {
      throw new DocumentWorkerProtocolException(
          "Document search snippet length is invalid");
    }
  }
}
