package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentOverviewMetadata(int schemaVersion,
                                       String sourceSha256,
                                       String mediaType,
                                       String title,
                                       long textBytes,
                                       int outlineCount,
                                       int pageCount) {}
