package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentWorkerPayloadDescriptor(
    DocumentWorkerPayloadRole role,
    @JsonProperty("media_type") String mediaType,
    long length
) {}
