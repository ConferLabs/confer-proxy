package org.moxie.confer.proxy.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

class FragmentedInputStream extends InputStream {

  private final byte[] content;
  private final int    maximumReadSize;

  private int     closeCalls;
  private int     position;
  private boolean closed;

  FragmentedInputStream(byte[] content,
                        int maximumReadSize)
  {
    this.content = Objects.requireNonNull(content, "content").clone();
    if (maximumReadSize <= 0) {
      throw new IllegalArgumentException("maximumReadSize must be positive");
    }
    this.maximumReadSize = maximumReadSize;
  }

  @Override
  public int read() throws IOException {
    requireOpen();
    if (position >= content.length) {
      return -1;
    }
    return Byte.toUnsignedInt(content[position++]);
  }

  @Override
  public int read(byte[] destination,
                  int offset,
                  int length)
    throws IOException
  {
    requireOpen();
    Objects.checkFromIndexSize(offset, length, destination.length);
    if (length == 0) {
      return 0;
    }
    if (position >= content.length) {
      return -1;
    }

    int copied = Math.min(
        Math.min(length, maximumReadSize),
        content.length - position);
    System.arraycopy(content, position, destination, offset, copied);
    position += copied;
    return copied;
  }

  @Override
  public void close() {
    closeCalls++;
    closed = true;
  }

  int closeCalls() {
    return closeCalls;
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Test input stream is closed");
    }
  }
}
