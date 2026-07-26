package org.moxie.confer.proxy.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

class FaultInjectingOutputStream extends OutputStream {

  private final ByteArrayOutputStream content = new ByteArrayOutputStream();
  private final int                   failingWrite;
  private final boolean               failingFlush;
  private final boolean               failingClose;

  private int closeCalls;
  private int flushCalls;
  private int nonEmptyWrites;

  FaultInjectingOutputStream(int failingWrite,
                             boolean failingFlush,
                             boolean failingClose)
  {
    this.failingWrite = failingWrite;
    this.failingFlush = failingFlush;
    this.failingClose = failingClose;
  }

  @Override
  public void write(int value) throws IOException {
    write(new byte[] {(byte) value}, 0, 1);
  }

  @Override
  public void write(byte[] source,
                    int offset,
                    int length)
    throws IOException
  {
    Objects.checkFromIndexSize(offset, length, source.length);
    if (length == 0) {
      return;
    }

    nonEmptyWrites++;
    if (nonEmptyWrites == failingWrite) {
      throw new IOException("Injected write failure");
    }
    content.write(source, offset, length);
  }

  @Override
  public void flush() throws IOException {
    flushCalls++;
    if (failingFlush) {
      throw new IOException("Injected flush failure");
    }
  }

  @Override
  public void close() throws IOException {
    closeCalls++;
    if (failingClose) {
      throw new IOException("Injected close failure");
    }
  }

  byte[] content() {
    return content.toByteArray();
  }

  int closeCalls() {
    return closeCalls;
  }

  int flushCalls() {
    return flushCalls;
  }
}
