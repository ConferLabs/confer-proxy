package org.moxie.confer.proxy.images;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Speaks the normalizer's multiplexed connection protocol on a Unix socket. Each image is
 * answered on its own thread, so concurrent images' responses interleave, and the server records
 * which images arrived on which connection.
 */
final class FakeNormalizerServer implements AutoCloseable {

  @FunctionalInterface
  interface Behavior {
    void respond(String image, Responder responder) throws IOException, InterruptedException;
  }

  /** Answers with a 1x1 frame whose pixels are 7, 8, 9. */
  static final Behavior ONE_PIXEL = (image, responder) -> {
    responder.header(1, 1);
    responder.pixels(new byte[] { 7, 8, 9 });
    responder.done();
  };

  private static final int HEADER = 1;
  private static final int PIXELS = 2;
  private static final int DONE   = 3;
  private static final int FAILED = 4;

  private final Path                directory;
  private final Path                socket;
  private final ServerSocketChannel server;
  private final List<List<String>>  connections  = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger       open         = new AtomicInteger();
  private final AtomicInteger       peakInFlight = new AtomicInteger();
  private volatile Behavior         behavior     = ONE_PIXEL;

  FakeNormalizerServer() throws IOException {
    // Unix socket paths are limited to about 100 bytes, so stay out of long temp directories.
    directory = Files.createTempDirectory(Path.of("/tmp"), "normalizer");
    socket    = directory.resolve("n.sock");
    server    = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket));

    Thread.ofVirtual().start(this::acceptConnections);
  }

  Path socket() {
    return socket;
  }

  void behave(Behavior behavior) {
    this.behavior = behavior;
  }

  int accepted() {
    return connections.size();
  }

  int open() {
    return open.get();
  }

  /** The most images one connection had in flight at once. */
  int peakInFlight() {
    return peakInFlight.get();
  }

  /** The images each connection received, in the order their last chunk arrived. */
  List<List<String>> received() {
    synchronized (connections) {
      List<List<String>> received = new ArrayList<>();
      for (List<String> images : connections) {
        synchronized (images) {
          received.add(List.copyOf(images));
        }
      }
      return received;
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    Files.deleteIfExists(socket);
    Files.deleteIfExists(directory);
  }

  private void acceptConnections() {
    while (server.isOpen()) {
      try {
        SocketChannel connection = server.accept();
        List<String>  images     = Collections.synchronizedList(new ArrayList<>());
        connections.add(images);
        Thread.ofVirtual().start(() -> serve(connection, images));
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void serve(SocketChannel connection, List<String> images) {
    open.incrementAndGet();

    try (connection) {
      DataInputStream                     input    = new DataInputStream(Channels.newInputStream(connection));
      DataOutputStream                    output   = new DataOutputStream(Channels.newOutputStream(connection));
      Map<Integer, ByteArrayOutputStream> partial  = new HashMap<>();
      AtomicInteger                       inFlight = new AtomicInteger();

      while (true) {
        int id     = input.readInt();
        int length = input.readInt();

        ByteArrayOutputStream image = partial.get(id);
        if (image == null) {
          image = new ByteArrayOutputStream();
          partial.put(id, image);
          peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        }

        if (length > 0) {
          image.write(input.readNBytes(length));
          continue;
        }

        partial.remove(id);
        String name = image.toString(StandardCharsets.US_ASCII);
        images.add(name);

        Responder responder = new Responder(id, output, connection);
        Thread.ofVirtual().start(() -> {
          try {
            behavior.respond(name, responder);
          } catch (IOException | InterruptedException ended) {
            // The connection closed while answering.
          } finally {
            inFlight.decrementAndGet();
          }
        });
      }
    } catch (EOFException ended) {
      // The client closed the connection.
    } catch (IOException ended) {
      // The connection was closed under the reader.
    } finally {
      open.decrementAndGet();
    }
  }

  /** Writes one image's messages, each whole, so concurrent images interleave cleanly. */
  static final class Responder {

    private final int              id;
    private final DataOutputStream output;
    private final SocketChannel    connection;

    Responder(int id, DataOutputStream output, SocketChannel connection) {
      this.id         = id;
      this.output     = output;
      this.connection = connection;
    }

    void header(long width, long height) throws IOException {
      message(HEADER, ByteBuffer.allocate(8).putInt((int) width).putInt((int) height).array());
    }

    void pixels(byte[] bytes) throws IOException {
      message(PIXELS, bytes);
    }

    void done() throws IOException {
      message(DONE, new byte[0]);
    }

    void failed() throws IOException {
      message(FAILED, new byte[0]);
    }

    void message(int kind, byte[] payload) throws IOException {
      synchronized (output) {
        output.writeInt(id);
        output.writeByte(kind);
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
      }
    }

    void closeConnection() throws IOException {
      connection.close();
    }
  }
}
