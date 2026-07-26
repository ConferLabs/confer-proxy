package org.moxie.confer.proxy.documents.worker.requests;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadDescriptor;

import java.util.List;

public record DocumentWorkerRequestHeader(int version,
                                          String operation,
                                          @JsonUnwrapped DocumentWorkerRequest parameters,
                                          List<DocumentWorkerPayloadDescriptor> payloads)
{
}
