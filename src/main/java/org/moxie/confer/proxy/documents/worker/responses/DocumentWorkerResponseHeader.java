package org.moxie.confer.proxy.documents.worker.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadDescriptor;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentWorkerResponseHeader<T>(Integer version,
                                              DocumentWorkerResponseStatus status,
                                              T result,
                                              DocumentWorkerError error,
                                              List<DocumentWorkerPayloadDescriptor> payloads)
{
  public DocumentWorkerResponseHeader {
    payloads = payloads == null ? List.of() : List.copyOf(payloads);
  }
}
