package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Objects;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentOverviewResult(
    String attachmentId,
    String filename,
    DocumentOverviewMetadata metadata,
    List<DocumentOutlineLocation> outline,
    boolean outlineTruncated,
    List<DocumentPageReference> labeledPages,
    Integer firstPageNumber,
    @JsonInclude(JsonInclude.Include.NON_NULL) String content,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning)
{
  public DocumentOverviewResult {
    Objects.requireNonNull(metadata, "metadata");
    outline = List.copyOf(outline);
    labeledPages = List.copyOf(labeledPages);
  }
}
