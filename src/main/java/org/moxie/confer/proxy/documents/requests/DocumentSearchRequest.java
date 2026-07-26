package org.moxie.confer.proxy.documents.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DocumentSearchRequest(
    @NotEmpty @Size(max = 8) @Valid
    List<@NotNull DocumentSearchQuery> queries,
    @JsonProperty("attachment_id") String attachmentId)
{
  public DocumentSearchRequest {
    if (queries != null) {
      queries = List.copyOf(queries);
    }
  }
}
