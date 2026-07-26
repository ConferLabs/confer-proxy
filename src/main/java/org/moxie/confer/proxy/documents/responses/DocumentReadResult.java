package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentReadResult(
    String attachmentId,
    String filename,
    int pageNumber,
    int pageCount,
    int totalPages,
    List<DocumentPageReference> pages,
    List<DocumentVisualContent> visualContent,
    String text,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning)
{
  public DocumentReadResult {
    pages = List.copyOf(pages);
    visualContent = List.copyOf(visualContent);
  }
}
