package org.moxie.confer.proxy.documents.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LegacyDocumentExtractionRequest(
    @NotBlank String filename,
    @JsonProperty("content_type") String contentType,
    @JsonProperty("total_length") @NotNull @Positive @Max(256L * 1024 * 1024) Long totalLength,
    Boolean ocr,
    @JsonProperty("table_structure") Boolean tableStructure,
    @JsonProperty("include_images") Boolean includeImages,
    @JsonProperty("image_export_mode") String imageExportMode
) {}
