package org.moxie.confer.proxy.documents.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record DocumentExtractionRequest(
    @NotBlank String filename,
    @JsonProperty("content_type") String contentType,
    @JsonProperty("total_length") @NotNull @Positive @Max(256L * 1024 * 1024) Long totalLength,
    @JsonProperty("source_object_key") String sourceObjectKey,
    @JsonProperty("encryption_key") String encryptionKey,
    @JsonProperty("inline_text_max_characters") @PositiveOrZero Long inlineTextMaxCharacters
) {}
