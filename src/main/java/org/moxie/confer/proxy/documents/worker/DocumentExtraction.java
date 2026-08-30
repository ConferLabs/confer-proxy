package org.moxie.confer.proxy.documents.worker;

import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class DocumentExtraction implements ManagedResource {

  private final DocumentWorkerLease        worker;
  private final DocumentExtractionResponse response;

  private boolean closed;

  DocumentExtraction(DocumentWorkerLease worker,
                     DocumentExtractionResponse response)
  {
    this.worker   = Objects.requireNonNull(worker, "worker");
    this.response = Objects.requireNonNull(response, "response");
  }

  public long contentLength() {
    return response.contentLength();
  }

  public synchronized void writeTo(OutputStream output) throws IOException {
    if (closed) {
      throw new IOException("Document extraction is closed");
    }
    response.writeTo(output);
  }

  public long jsonEscapedTextBytes() {
    return response.jsonEscapedTextBytes();
  }

  public synchronized void consumeText(DocumentWorkerPayloadHandler handler) throws IOException {
    if (closed) {
      throw new IOException("Document extraction is closed");
    }
    response.consumeText(handler);
  }

  public synchronized void consumePayloads(DocumentWorkerPayloadHandler handler)
    throws IOException
  {
    if (closed) {
      throw new IOException("Document extraction is closed");
    }
    response.consumePayloads(handler);
  }

  public long textBytes() {
    return response.textBytes();
  }

  public long textCharacters() {
    return response.textCharacters();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    worker.close();
  }
}
