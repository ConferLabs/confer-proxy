package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentSearchDocumentResult(
    String attachmentId,
    String filename,
    List<DocumentSearchMatch> hits,
    boolean hasMoreResults,
    @JsonProperty("matchCount") int matchCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning)
    implements DocumentSearchToolResult
{
  public DocumentSearchDocumentResult {
    hits = List.copyOf(hits);
  }
}
