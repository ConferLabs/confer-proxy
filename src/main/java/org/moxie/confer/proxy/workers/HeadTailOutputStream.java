package org.moxie.confer.proxy.workers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

class HeadTailOutputStream extends OutputStream {

  private final int                   headCapacity;
  private final long                  maximumTotalBytes;
  private final byte[]                tail;
  private final ByteArrayOutputStream head;
  private final CountDownLatch        closedSignal = new CountDownLatch(1);

  private int  tailStart;
  private int  tailSize;
  private long totalBytes;
  private boolean limitExceeded;
  private boolean closed;

  HeadTailOutputStream(int maximumBytes,
                       long maximumTotalBytes)
  {
    if (maximumBytes <= 0) {
      throw new IllegalArgumentException("Maximum retained bytes must be positive");
    }
    if (maximumTotalBytes < 0) {
      throw new IllegalArgumentException("Maximum total bytes must not be negative");
    }

    headCapacity           = maximumBytes / 2;
    this.maximumTotalBytes = maximumTotalBytes;
    tail                  = new byte[maximumBytes - headCapacity];
    head                  = new ByteArrayOutputStream(headCapacity);
  }

  @Override
  public synchronized void write(int value) throws IOException {
    requireOpen();
    if (totalBytes == maximumTotalBytes) {
      limitExceeded = true;
      throw new IOException("Output limit exceeded");
    }
    totalBytes++;
    if (head.size() < headCapacity) {
      head.write(value);
      return;
    }
    appendToTail((byte) value);
  }

  @Override
  public synchronized void write(byte[] source,
                                 int    offset,
                                 int    length)
      throws IOException
  {
    Objects.checkFromIndexSize(offset, length, source.length);
    requireOpen();
    if (length == 0) {
      return;
    }
    if (limitExceeded) {
      throw new IOException("Output limit exceeded");
    }

    int accepted = (int) Math.min(length, maximumTotalBytes - totalBytes);
    totalBytes += accepted;

    int headBytes = Math.min(accepted, headCapacity - head.size());
    head.write(source, offset, headBytes);
    for (int index = offset + headBytes; index < offset + accepted; index++) {
      appendToTail(source[index]);
    }
    if (accepted < length) {
      limitExceeded = true;
      throw new IOException("Output limit exceeded");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    closedSignal.countDown();
  }

  void awaitClose() {
    try {
      closedSignal.await();
    } catch (InterruptedException error) {
      throw new AssertionError(error);
    }
  }

  synchronized boolean isLimitExceeded() {
    return limitExceeded;
  }

  synchronized String getOutput() {
    ByteArrayOutputStream result = new ByteArrayOutputStream(
        head.size() + tailSize + 64);
    result.writeBytes(head.toByteArray());
    if (isTruncated()) {
      long omitted = totalBytes - head.size() - tailSize;
      result.writeBytes(("\n[... " + omitted + " bytes omitted ...]\n")
          .getBytes(StandardCharsets.UTF_8));
    }
    writeTailTo(result);
    return result.toString(StandardCharsets.UTF_8);
  }

  synchronized boolean isTruncated() {
    return totalBytes > head.size() + tailSize;
  }

  private void appendToTail(byte value) {
    if (tailSize < tail.length) {
      tail[(tailStart + tailSize) % tail.length] = value;
      tailSize++;
      return;
    }
    tail[tailStart] = value;
    tailStart = (tailStart + 1) % tail.length;
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Output is closed");
    }
  }

  private void writeTailTo(ByteArrayOutputStream destination) {
    int firstLength = Math.min(tailSize, tail.length - tailStart);
    destination.write(tail, tailStart, firstLength);
    destination.write(tail, 0, tailSize - firstLength);
  }
}
