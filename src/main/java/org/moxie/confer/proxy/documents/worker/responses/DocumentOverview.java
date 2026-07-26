package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentOverview(@Valid @NotNull DocumentArtifactMetadata metadata,
                               List<DocumentOutlineEntry> outline,
                               boolean outlineTruncated,
                               List<DocumentContainerReference> labeledContainers,
                               Integer firstChunkId,
                               String content)
{
  public DocumentOverview {
    outline = List.copyOf(outline);
    labeledContainers = List.copyOf(labeledContainers);
  }
}
