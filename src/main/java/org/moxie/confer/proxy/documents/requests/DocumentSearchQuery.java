package org.moxie.confer.proxy.documents.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DocumentSearchQuery(
    @NotEmpty @Size(max = 8)
    List<@NotBlank @Size(max = 256) String> all)
{
  public DocumentSearchQuery {
    if (all != null) {
      all = List.copyOf(all);
    }
  }
}
