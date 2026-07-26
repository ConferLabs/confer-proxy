package org.moxie.confer.proxy.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

class FaultInjectingInputStream extends InputStream {

  private final byte[]  content;
  private final int     failingAfter;
  private final boolean failingClose;

  private int closeCalls;
  private int position;

  FaultInjectingInputStream(byte[] content,
                            int failingAfter,
                            boolean failingClose)
  {
    this.content      = Objects.requireNonNull(content, "content").clone();
    this.failingAfter = failingAfter;
    this.failingClose = failingClose;
  }

  @Override
  public int read() throws IOException {
    byte[] destination = new byte[1];
    int read = read(destination, 0, 1);
    return read < 0 ? -1 : Byte.toUnsignedInt(destination[0]);
  }

  @Override
  public int read(byte[] destination,
                  int offset,
                  int length)
    throws IOException
  {
    Objects.checkFromIndexSize(offset, length, destination.length);
    if (length == 0) {
      return 0;
    }
    if (failingAfter >= 0 && position >= failingAfter) {
      throw new IOException("Injected read failure");
    }
    if (position >= content.length) {
      return -1;
    }

    int copied = Math.min(length, content.length - position);
    if (failingAfter >= 0) {
      copied = Math.min(copied, failingAfter - position);
    }
    System.arraycopy(content, position, destination, offset, copied);
    position += copied;
    return copied;
  }

  @Override
  public void close() throws IOException {
    closeCalls++;
    if (failingClose) {
      throw new IOException("Injected close failure");
    }
  }

  int closeCalls() {
    return closeCalls;
  }
}
