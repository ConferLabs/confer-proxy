package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentSearchMatch(int pageNumber,
                                  int pageCount,
                                  String excerpt,
                                  List<DocumentPageReference> pages,
                                  List<DocumentVisualContent> visualContent)
{
  public DocumentSearchMatch {
    pages = List.copyOf(pages);
    visualContent = List.copyOf(visualContent);
  }
}
