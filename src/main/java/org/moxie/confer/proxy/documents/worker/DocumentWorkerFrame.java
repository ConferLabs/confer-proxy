package org.moxie.confer.proxy.documents.worker;

import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerError;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseHeader;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseStatus;

import java.util.List;
import java.util.Map;

final class DocumentWorkerFrame<T> {

  static final int MAX_HEADER_BYTES = 64 * 1024;

  private final DocumentWorkerResponseHeader<T> header;
  private final DocumentWorkerPayloadManifest   payloads;
  private final byte[]                          encodedHeader;

  DocumentWorkerFrame(DocumentWorkerResponseHeader<T> header,
                      byte[] encodedHeader)
    throws DocumentWorkerProtocolException
  {
    if (encodedHeader.length < 2 || encodedHeader.length > MAX_HEADER_BYTES) {
      throw new DocumentWorkerProtocolException(
          "Document worker response header length is invalid");
    }
    this.header        = header;
    this.payloads      = new DocumentWorkerPayloadManifest(header.payloads());
    this.encodedHeader = encodedHeader.clone();
  }

  DocumentWorkerError error() {
    return header.error();
  }

  T result() {
    return header.result();
  }

  DocumentWorkerResponseStatus status() {
    return header.status();
  }

  List<DocumentWorkerPayloadDescriptor> payloads() {
    return payloads.descriptors();
  }

  long payloadBytes() {
    return payloads.totalBytes();
  }

  long contentLength() {
    return Integer.BYTES + encodedHeader.length + payloadBytes();
  }

  byte[] encodedHeader() {
    return encodedHeader.clone();
  }

  DocumentWorkerResponse<T> response(
      Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads)
  {
    return new DocumentWorkerResponse<>(status(), result(), error(), payloads);
  }
}
