package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DocumentSearchCollectionResult(
    List<DocumentSearchFileResult> files,
    @JsonProperty("failedFiles") List<DocumentSearchFailure> failedFiles,
    @JsonProperty("matchCount") int matchCount,
    boolean truncated,
    boolean partial,
    @JsonInclude(JsonInclude.Include.NON_NULL) String message)
    implements DocumentSearchToolResult
{
  public DocumentSearchCollectionResult {
    files = List.copyOf(files);
    failedFiles = List.copyOf(failedFiles);
  }

  public DocumentSearchCollectionResult withFiles(List<DocumentSearchFileResult> included,
                                                  boolean resultTruncated)
  {
    return new DocumentSearchCollectionResult(
        included,
        failedFiles,
        matchCount,
        truncated || resultTruncated,
        partial,
        message);
  }
}
