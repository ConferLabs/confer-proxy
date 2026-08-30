package org.moxie.confer.proxy.documents.worker;

import org.moxie.confer.proxy.documents.worker.responses.DocumentExtractionMetadata;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class DocumentExtractionResponse implements AutoCloseable {

  private final DocumentWorkerConnection   connection;
  private final DocumentExtractionMetadata metadata;
  private final long                       contentLength;

  private boolean closed;

  DocumentExtractionResponse(DocumentWorkerConnection connection,
                             long contentLength,
                             DocumentExtractionMetadata metadata)
  {
    this.connection    = Objects.requireNonNull(connection, "connection");
    this.contentLength = contentLength;
    this.metadata      = Objects.requireNonNull(metadata, "metadata");
  }

  public long contentLength() {
    return contentLength;
  }

  public synchronized void writeTo(OutputStream output) throws IOException {
    if (closed) {
      throw new IOException("Document extraction response is closed");
    }
    connection.writeExtractionResponseTo(output);
  }

  public long jsonEscapedTextBytes() {
    return metadata.jsonEscapedTextBytes();
  }

  public synchronized void consumeText(DocumentWorkerPayloadHandler handler) throws IOException {
    if (closed) {
      throw new IOException("Document extraction response is closed");
    }
    connection.consumeExtractionText(handler);
  }

  public synchronized void consumePayloads(DocumentWorkerPayloadHandler handler)
    throws IOException
  {
    if (closed) {
      throw new IOException("Document extraction response is closed");
    }
    connection.consumeExtractionPayloads(handler);
  }

  public long textBytes() {
    return metadata.textBytes();
  }

  public long textCharacters() {
    return metadata.textCharacters();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    connection.close();
  }
}
