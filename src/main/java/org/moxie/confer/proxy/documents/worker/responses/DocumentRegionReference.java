package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentRegionReference(String id,
                                      int containerNumber,
                                      String type,
                                      double confidence,
                                      boolean renderable) {}
