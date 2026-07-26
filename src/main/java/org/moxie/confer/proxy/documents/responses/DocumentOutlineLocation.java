package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentOutlineLocation(int level,
                                      String title,
                                      int pageNumber) {}
