package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentArtifactMetadata(
    @NotNull @Positive Integer schemaVersion,
    @NotNull String sourceSha256,
    @NotBlank String mediaType,
    @NotNull String title,
    @NotNull @PositiveOrZero Long textBytes,
    @NotNull @PositiveOrZero Integer containerCount,
    @NotNull @PositiveOrZero Integer outlineCount,
    @PositiveOrZero Integer pageCount
) {}
