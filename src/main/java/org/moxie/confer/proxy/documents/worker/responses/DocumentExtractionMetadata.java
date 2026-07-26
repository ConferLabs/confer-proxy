package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentExtractionMetadata(
    @NotNull @PositiveOrZero Long textBytes,
    @NotNull @PositiveOrZero Long textCharacters,
    @NotNull @PositiveOrZero Long jsonEscapedTextBytes
) {}
