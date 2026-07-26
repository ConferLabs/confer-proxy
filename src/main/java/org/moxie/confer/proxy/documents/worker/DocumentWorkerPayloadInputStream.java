package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * A callback-scoped view of one payload in a document worker response.
 *
 * <p>Closing this stream never closes the worker connection. The stream is
 * invalidated when its callback returns, and cannot read into the following
 * payload.</p>
 */
final class DocumentWorkerPayloadInputStream extends InputStream {

  private final InputStream input;
  private final long        length;

  private long    remaining;
  private boolean closed;
  private boolean valid = true;

  DocumentWorkerPayloadInputStream(InputStream input, long length) {
    this.input     = Objects.requireNonNull(input, "input");
    this.length    = length;
    this.remaining = length;
  }

  @Override
  public int read() throws IOException {
    byte[] value = new byte[1];
    int read = read(value, 0, 1);
    return read < 0 ? -1 : Byte.toUnsignedInt(value[0]);
  }

  @Override
  public int read(byte[] buffer, int offset, int requested) throws IOException {
    Objects.checkFromIndexSize(offset, requested, buffer.length);
    requireReadable();

    if (requested == 0) {
      return 0;
    }
    if (remaining == 0) {
      return -1;
    }

    int read = input.read(
        buffer,
        offset,
        (int) Math.min(requested, remaining));
    if (read < 0) {
      throw new IOException("Document worker response payload is truncated");
    }

    remaining -= read;
    return read;
  }

  @Override
  public int available() throws IOException {
    requireReadable();
    return (int) Math.min(input.available(), remaining);
  }

  @Override
  public void close() {
    closed = true;
  }

  long consumedBytes() {
    return length - remaining;
  }

  boolean fullyConsumed() {
    return remaining == 0;
  }

  void invalidate() {
    valid = false;
  }

  private void requireReadable() throws IOException {
    if (!valid) {
      throw new IOException("Document worker response payload is no longer available");
    }
    if (closed) {
      throw new IOException("Document worker response payload is closed");
    }
  }
}
