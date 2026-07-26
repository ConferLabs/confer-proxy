package org.moxie.confer.proxy.documents.worker;

import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;

import java.util.Map;

public record DocumentWorkerTestFrame(
    Map<String, Object> header,
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads)
{}
