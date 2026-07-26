package org.moxie.confer.proxy.entities;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.moxie.confer.proxy.images.ImageReference;

import java.util.List;
import java.util.Map;

public record ChatRequest(
  @NotEmpty @Valid List<@NotNull Message> messages,
  @NotBlank String model,
  @DecimalMin("0.0") Double temperature,
  @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double topP,
  @Min(-1) Integer topK,
  @DecimalMin("0.0") @DecimalMax("1.0") Double minP,
  @DecimalMin("-2.0") @DecimalMax("2.0") Double presencePenalty,
  @DecimalMin("-2.0") @DecimalMax("2.0") Double frequencyPenalty,
  @DecimalMin(value = "0.0", inclusive = false) Double repetitionPenalty,
  @Positive Integer maxTokens,
  @NotNull Boolean stream,
  Boolean json,
  Boolean thinking,
  Boolean webSearch,
  @Size(max = 128) @Valid List<@NotNull ClientTool> clientTools,
  @Size(max = 1_000) @Valid List<@NotNull DocumentReference> documents
) {
  public ChatRequest(
      List<Message> messages,
      String model,
      Double temperature,
      Double topP,
      Integer topK,
      Double minP,
      Double presencePenalty,
      Double frequencyPenalty,
      Double repetitionPenalty,
      Integer maxTokens,
      Boolean stream,
      Boolean json,
      Boolean thinking,
      Boolean webSearch,
      List<ClientTool> clientTools)
  {
    this(
        messages,
        model,
        temperature,
        topP,
        topK,
        minP,
        presencePenalty,
        frequencyPenalty,
        repetitionPenalty,
        maxTokens,
        stream,
        json,
        thinking,
        webSearch,
        clientTools,
        null);
  }

  public enum Role {
    user, assistant, system, developer, tool_call, tool_response
  }

  public record Message(
    @NotNull Role role,
    @NotNull String content,
    List<@NotNull ImageReference> imageRefs
  ) {}

  public record ClientTool(
    @NotBlank @Size(max = 64) String name,
    @NotNull String description,
    @NotNull Map<String, Object> parameters
  ) {}
}
