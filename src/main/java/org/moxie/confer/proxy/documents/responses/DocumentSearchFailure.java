package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentSearchFailure(String attachmentId,
                                    String filename,
                                    String error) {}
