package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentPageViewContent(String attachmentId,
                                      String filename,
                                      String mediaType,
                                      int width,
                                      int height,
                                      int pageNumber,
                                      String message) {}
