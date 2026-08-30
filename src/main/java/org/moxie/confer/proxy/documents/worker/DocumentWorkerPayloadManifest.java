package org.moxie.confer.proxy.documents.worker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class DocumentWorkerPayloadManifest {

  private static final long MAX_PAYLOAD_BYTES       = 256L * 1024 * 1024;
  private static final long MAX_TOTAL_PAYLOAD_BYTES = 320L * 1024 * 1024;

  private final List<DocumentWorkerPayloadDescriptor> descriptors;
  private final long                                  totalBytes;

  DocumentWorkerPayloadManifest(List<DocumentWorkerPayloadDescriptor> descriptors)
    throws DocumentWorkerProtocolException
  {
    if (descriptors == null) {
      throw new DocumentWorkerProtocolException(
          "Document worker payload descriptors are missing");
    }

    Set<DocumentWorkerPayloadRole> roles = new HashSet<>();
    long total = 0;

    for (DocumentWorkerPayloadDescriptor descriptor : descriptors) {
      if (descriptor == null || descriptor.role() == null) {
        throw new DocumentWorkerProtocolException("Document worker payload role is invalid");
      }
      if (!roles.add(descriptor.role())) {
        throw new DocumentWorkerProtocolException("Document worker returned a duplicate payload");
      }
      if (!descriptor.role().acceptsMediaType(descriptor.mediaType())) {
        throw new DocumentWorkerProtocolException(
            "Document worker payload media type is invalid");
      }
      if (descriptor.length() < 0 || descriptor.length() > MAX_PAYLOAD_BYTES) {
        throw new DocumentWorkerProtocolException("Document worker payload length is invalid");
      }

      total += descriptor.length();
      if (total > MAX_TOTAL_PAYLOAD_BYTES) {
        throw new DocumentWorkerProtocolException(
            "Document worker payloads exceed total limit");
      }
    }

    this.descriptors = List.copyOf(descriptors);
    this.totalBytes  = total;
  }

  List<DocumentWorkerPayloadDescriptor> descriptors() {
    return descriptors;
  }

  long totalBytes() {
    return totalBytes;
  }
}
