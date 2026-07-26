package org.moxie.confer.proxy.documents.worker.responses;

public record DocumentWorkerResponsePayload(String mediaType, byte[] content) {}
