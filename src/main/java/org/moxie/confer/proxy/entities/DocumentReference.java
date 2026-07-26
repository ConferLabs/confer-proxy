package org.moxie.confer.proxy.entities;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DocumentReference(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,200}$") String attachmentId,
    @NotBlank @Size(max = 1_024) String filename,
    @NotBlank String contentType,
    @Positive @Max(256L * 1024 * 1024) long sourceBytes,
    @NotBlank String sourceObjectKey,
    @NotBlank String encryptionKey
) {}
