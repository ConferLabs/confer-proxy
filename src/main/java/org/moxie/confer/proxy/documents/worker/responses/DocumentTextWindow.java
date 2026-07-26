package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentTextWindow(int containerStart,
                                 int containerEnd,
                                 int totalContainers,
                                 List<DocumentRegionReference> regions,
                                 List<DocumentContainerReference> containers)
{
  public DocumentTextWindow {
    regions = List.copyOf(regions);
    containers = List.copyOf(containers);
  }
}
