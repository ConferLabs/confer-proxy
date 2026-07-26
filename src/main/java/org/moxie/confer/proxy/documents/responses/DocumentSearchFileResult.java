package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentSearchFileResult(
    String attachmentId,
    String filename,
    @JsonProperty("matchCount") int matchCount,
    boolean hasMoreResults,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning,
    List<DocumentSearchMatch> results)
{
  public DocumentSearchFileResult {
    results = List.copyOf(results);
  }

  public DocumentSearchFileResult withIncludedResults(List<DocumentSearchMatch> included) {
    return new DocumentSearchFileResult(
        attachmentId,
        filename,
        matchCount,
        hasMoreResults || included.size() < results.size(),
        warning,
        included);
  }
}
