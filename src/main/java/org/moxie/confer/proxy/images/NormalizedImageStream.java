package org.moxie.confer.proxy.images;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A normalized image on its way from its pool's sandboxed normalizer to vLLM. The connection's
 * reader hands it each message as it arrives and waits until it is taken, so at most one pixel
 * chunk is in memory. It holds one of the connection's slots until closed.
 *
 * An image that is not through by its deadline ends its connection: either the normalizer missed
 * its own, shorter deadline, or this image stopped being read and holds up the reader for every
 * other image on the connection.
 */
public final class NormalizedImageStream implements Closeable {

  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private final NormalizerConnection              connection;
  private final int                               id;
  private final long                              deadline;
  private final SynchronousQueue<NormalizerEvent> events = new SynchronousQueue<>();
  private final AtomicBoolean                     closed = new AtomicBoolean();

  private volatile NormalizedImage image;
  private volatile boolean         connectionClosed;

  NormalizedImageStream(NormalizerConnection connection,
                        int                  id,
                        Duration             deadline)
  {
    this.connection = connection;
    this.id         = id;
    this.deadline   = System.nanoTime() + deadline.toNanos();
  }

  public NormalizedImage image() {
    return image;
  }

  /** Writes the PPM header and then the pixels as they arrive from the normalizer. */
  public void writeTo(OutputStream output) throws IOException {
    output.write(image.ppmHeader());

    long remaining = image.pixelBytes();
    while (remaining > 0) {
      switch (next()) {
        case NormalizerEvent.Pixels pixels -> {
          if (pixels.bytes().length > remaining) {
            throw new IOException("Normalizer sent more pixels than its frame holds");
          }
          output.write(pixels.bytes());
          remaining -= pixels.bytes().length;
        }
        case NormalizerEvent.Header _, NormalizerEvent.Done _, NormalizerEvent.Failed _ ->
            throw new IOException("Normalized image ended before its pixels");
      }
    }

    switch (next()) {
      case NormalizerEvent.Done _ -> {}
      case NormalizerEvent.Header _, NormalizerEvent.Pixels _, NormalizerEvent.Failed _ ->
          throw new IOException("Normalizer failed the image after its pixels");
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      connection.finished(id);
    }
  }

  /** Waits for the frame header, which arrives once the normalizer has decoded the image. */
  NormalizedImage awaitHeader() throws IOException, InvalidNormalizedImageException {
    image = switch (next()) {
      case NormalizerEvent.Header header -> NormalizedImage.of(header.width(), header.height());
      case NormalizerEvent.Failed _ -> throw new InvalidNormalizedImageException("The normalizer rejected the image");
      case NormalizerEvent.Pixels _, NormalizerEvent.Done _ ->
          throw new IOException("Image normalizer sent no frame header");
    };
    return image;
  }

  /** Called by the connection's reader; returns once the event is taken or the stream is gone. */
  void deliver(NormalizerEvent event) throws IOException {
    try {
      while (!closed.get() && !connectionClosed) {
        long remaining = untilDeadline();
        if (events.offer(event, Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)) {
          return;
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while handing over a normalized image");
    }
  }

  void connectionClosed() {
    connectionClosed = true;
  }

  private NormalizerEvent next() throws IOException {
    try {
      while (true) {
        if (connectionClosed) {
          throw new IOException("Image normalizer connection ended");
        }
        if (closed.get()) {
          throw new IOException("Normalized image stream is closed");
        }

        long            remaining = untilDeadline();
        NormalizerEvent event     = events.poll(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS);
        if (event != null) {
          return event;
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while waiting for the image normalizer");
    }
  }

  /** Nanoseconds left before the deadline; once it has passed, ends the connection and throws. */
  private long untilDeadline() throws IOException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      connection.close();
      throw new IOException("Normalized image missed its deadline");
    }
    return remaining;
  }
}
