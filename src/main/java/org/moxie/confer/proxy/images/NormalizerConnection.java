package org.moxie.confer.proxy.images;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One pool's connection to the normalizer socket, and so one sandboxed normalizer process, which
 * decodes each image in a fresh child (see connection.rs in image-normalizer).
 *
 * Several of the pool's images share it. Each is sent as chunks tagged with its id as it is
 * read, and a reader thread hands each response message to that image's stream as it arrives,
 * one at a time, so nothing is buffered. A protocol error or I/O failure ends the connection and
 * every image on it; messages for an image whose stream has closed are discarded.
 */
final class NormalizerConnection {

  private static final Logger log = LoggerFactory.getLogger(NormalizerConnection.class);

  /** The pool's images decoded at once; the normalizer accepts up to eight. */
  static final int MAX_IN_FLIGHT = 4;

  private static final int CHUNK_BYTES        = 64 * 1024;
  private static final int MAX_PIXELS_MESSAGE = 1 << 20;

  private static final int HEADER = 1;
  private static final int PIXELS = 2;
  private static final int DONE   = 3;
  private static final int FAILED = 4;

  private final String                              pool;
  private final SocketChannel                       channel;
  private final DataInputStream                     input;
  private final DataOutputStream                    output;
  private final Consumer<NormalizerConnection>      wentIdle;
  private final Semaphore                           slots      = new Semaphore(MAX_IN_FLIGHT);
  private final Map<Integer, NormalizedImageStream> streams    = new ConcurrentHashMap<>();
  private final AtomicInteger                       nextId     = new AtomicInteger();
  private final AtomicInteger                       checkedOut = new AtomicInteger();
  private final AtomicBoolean                       closed     = new AtomicBoolean();

  private NormalizerConnection(String                         pool,
                               SocketChannel                  channel,
                               Consumer<NormalizerConnection> wentIdle)
  {
    this.pool     = pool;
    this.channel  = channel;
    this.input    = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), CHUNK_BYTES));
    this.output   = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), CHUNK_BYTES + 2 * Integer.BYTES));
    this.wentIdle = wentIdle;
  }

  /** {@code wentIdle} runs each time the last checkout is returned. */
  static NormalizerConnection open(String                         pool,
                                   UnixDomainSocketAddress        socket,
                                   Consumer<NormalizerConnection> wentIdle)
      throws IOException
  {
    NormalizerConnection connection = new NormalizerConnection(pool, SocketChannel.open(socket), wentIdle);
    Thread.ofVirtual().name("image-normalizer-reader").start(connection::readResponses);
    return connection;
  }

  /**
   * Streams the image to the normalizer and waits for its frame header. The caller must have
   * {@link #checkOut checked out} the connection; the checkout passes to the returned stream.
   */
  NormalizedImageStream normalize(InputStream image,
                                  Duration    deadline)
      throws IOException, InvalidNormalizedImageException
  {
    NormalizedImageStream stream    = null;
    boolean               handedOff = false;

    try {
      if (!slots.tryAcquire(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("Timed out waiting for an image normalizer slot");
      }

      int id = nextId.getAndIncrement();
      stream = new NormalizedImageStream(this, id, deadline);
      streams.put(id, stream);

      if (closed.get()) {
        throw new IOException("Image normalizer connection is closed");
      }

      send(id, image);
      stream.awaitHeader();

      handedOff = true;
      return stream;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while waiting for an image normalizer slot");
    } finally {
      // Once handed off, the stream holds the slot and the checkout until it closes.
      if (!handedOff) {
        if (stream != null) {
          stream.close();
        } else {
          checkIn();
        }
      }
    }
  }

  /** Keeps the connection from closing as idle until the matching {@link #checkIn}. */
  void checkOut() {
    checkedOut.incrementAndGet();
  }

  void checkIn() {
    if (checkedOut.decrementAndGet() == 0) {
      wentIdle.accept(this);
    }
  }

  /** Called by a stream when it closes: frees its slot and the checkout it held. */
  void finished(int id) {
    streams.remove(id);
    slots.release();
    checkIn();
  }

  /**
   * Closes the connection if no image has it checked out, and reports whether it is now closed.
   * The caller must keep anyone from checking it out meanwhile.
   */
  boolean closeIfIdle() {
    if (checkedOut.get() == 0) {
      close();
    }
    return isClosed();
  }

  boolean isClosed() {
    return closed.get();
  }

  String pool() {
    return pool;
  }

  /** Ends the connection and every image on it, which returns their slots and checkouts. */
  void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    try {
      channel.close();
    } catch (IOException error) {
      log.warn("Image normalizer connection did not close cleanly: {}", error.toString());
    }

    for (NormalizedImageStream stream : streams.values()) {
      stream.connectionClosed();
      stream.close();
    }
  }

  /**
   * Sends the image as it is read and then ends it, even if reading it failed. A write blocks
   * only while the normalizer is not reading, which it ends the connection over at its own
   * deadline.
   */
  private void send(int id, InputStream image) throws IOException {
    byte[]      chunk         = new byte[CHUNK_BYTES];
    IOException sourceFailure = null;

    while (true) {
      int read;
      try {
        read = image.read(chunk);
      } catch (IOException error) {
        sourceFailure = error;
        break;
      }

      if (read == -1) {
        break;
      }
      if (read > 0) {
        writeChunk(id, chunk, read);
      }
    }

    writeChunk(id, chunk, 0);

    if (sourceFailure != null) {
      throw sourceFailure;
    }
  }

  private void writeChunk(int    id,
                          byte[] chunk,
                          int    length)
      throws IOException
  {
    try {
      synchronized (output) {
        output.writeInt(id);
        output.writeInt(length);
        output.write(chunk, 0, length);
        output.flush();
      }
    } catch (IOException error) {
      close();
      throw error;
    }
  }

  private void readResponses() {
    try {
      while (true) {
        int id     = input.readInt();
        int kind   = input.readUnsignedByte();
        int length = input.readInt();

        if (length < 0 || length > MAX_PIXELS_MESSAGE) {
          throw new IOException("Image normalizer message length is out of range");
        }
        if (Integer.compareUnsigned(id, nextId.get()) >= 0) {
          throw new IOException("Image normalizer answered an image that was never sent");
        }

        NormalizedImageStream stream = streams.get(id);

        switch (kind) {
          case HEADER -> {
            if (length != 2 * Integer.BYTES) {
              throw new IOException("Image normalizer header has the wrong length");
            }
            long width  = Integer.toUnsignedLong(input.readInt());
            long height = Integer.toUnsignedLong(input.readInt());
            deliver(stream, new NormalizerEvent.Header(width, height));
          }
          case PIXELS -> {
            if (length == 0) {
              throw new IOException("Image normalizer sent an empty pixels message");
            }
            byte[] pixels = new byte[length];
            input.readFully(pixels);
            deliver(stream, new NormalizerEvent.Pixels(pixels));
          }
          case DONE, FAILED -> {
            if (length != 0) {
              throw new IOException("Image normalizer outcome has a body");
            }
            deliver(stream, kind == DONE ? new NormalizerEvent.Done() : new NormalizerEvent.Failed());
          }
          default -> throw new IOException("Image normalizer sent an unknown message");
        }
      }
    } catch (EOFException | ClosedChannelException ended) {
      // The connection ended, from either side.
    } catch (IOException error) {
      log.warn("Image normalizer connection failed: {}", error.toString());
    } finally {
      close();
    }
  }

  /** Waits until the stream takes the event, or drops it for a stream that is gone. */
  private static void deliver(NormalizedImageStream stream,
                              NormalizerEvent       event)
      throws IOException
  {
    if (stream != null) {
      stream.deliver(event);
    }
  }
}
