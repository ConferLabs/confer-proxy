package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentExtractionResult(
    @JsonProperty("text_length") long textLength,
    @JsonInclude(JsonInclude.Include.NON_NULL) String markdown
) {

  public DocumentExtractionResult {
    if (textLength < 0) {
      throw new IllegalArgumentException("Extracted text length must not be negative");
    }
  }
}
