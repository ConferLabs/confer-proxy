package org.moxie.confer.proxy.images;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts untrusted images into {@link NormalizedImageStream}s in sandboxed processes, keeping
 * each client connection's images apart from every other's.
 *
 * A pool is one client websocket ({@link ImagePoolKey}). Each pool gets one connection to the
 * socket, and so one sandboxed normalizer process with its own dynamic user, no network, an empty
 * filesystem and a fixed set of system calls, which decodes each of the pool's images in a fresh
 * child, several at a time; a compromised decoder can reach nothing of another connection's.
 * Checking out a connection closes every other one that has been idle for longer than the grace,
 * so a pool keeps its connection across the gaps between its images and is gone soon after.
 */
@ApplicationScoped
public class ImageNormalizer {

  // Not configurable: the proxy sends decrypted images to whatever listens here, and the
  // configuration comes from the host's config disk.
  static final Path     SOCKET         = Path.of("/run/confer-image-normalizer/normalizer.sock");
  // The normalizer gives each image 30 seconds; this is longer, so its own report arrives first.
  static final Duration IMAGE_DEADLINE = Duration.ofSeconds(35);
  // Covers the gaps between a turn's images and between quick turns; an idle normalizer holds no
  // image data.
  static final Duration IDLE_GRACE     = Duration.ofSeconds(10);

  private final UnixDomainSocketAddress                   socket;
  private final Duration                                  imageDeadline;
  private final Duration                                  idleGrace;
  private final Map<String, NormalizerConnection>         connections = new HashMap<>();
  // Each idle connection with when it went idle, oldest first.
  private final LinkedHashMap<NormalizerConnection, Long> idle        = new LinkedHashMap<>();

  public ImageNormalizer() {
    this(SOCKET, IMAGE_DEADLINE, IDLE_GRACE);
  }

  ImageNormalizer(Path     socket,
                  Duration imageDeadline,
                  Duration idleGrace)
  {
    this.socket        = UnixDomainSocketAddress.of(socket);
    this.imageDeadline = imageDeadline;
    this.idleGrace     = idleGrace;
  }

  /**
   * Streams the image to the pool's connection and reads its frame header. The returned stream
   * holds one of the connection's slots until closed; its deadline starts now.
   */
  public NormalizedImageStream normalize(String      pool,
                                         InputStream image)
      throws IOException, InvalidNormalizedImageException
  {
    return checkOut(pool).normalize(image, imageDeadline);
  }

  @PreDestroy
  public synchronized void close() {
    for (NormalizerConnection connection : connections.values()) {
      connection.close();
    }
    connections.clear();
    idle.clear();
  }

  /** The connections the pool holds, including any that ended and are not yet dropped. */
  synchronized int trackedConnections() {
    return connections.size();
  }

  /** The pool's connection, opened if it has none, after closing those idle past the grace. */
  private synchronized NormalizerConnection checkOut(String pool) throws IOException {
    NormalizerConnection connection = connections.get(pool);
    if (connection != null) {
      idle.remove(connection);
    }
    closeConnectionsIdlePastTheGrace();

    if (connection == null || connection.isClosed()) {
      connection = NormalizerConnection.open(pool, socket, this::wentIdle);
      connections.put(pool, connection);
    }

    connection.checkOut();
    return connection;
  }

  /** Requires the lock. Stops at the first connection still within the grace. */
  private void closeConnectionsIdlePastTheGrace() {
    long cutoff = System.nanoTime() - idleGrace.toNanos();

    Iterator<Map.Entry<NormalizerConnection, Long>> entries = idle.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<NormalizerConnection, Long> entry = entries.next();
      if (entry.getValue() - cutoff > 0) {
        return;
      }

      entries.remove();
      // It stays open if it was checked out again between going idle and being listed.
      if (entry.getKey().closeIfIdle()) {
        connections.remove(entry.getKey().pool(), entry.getKey());
      }
    }
  }

  /** Called by a connection when its last checkout is returned. */
  private synchronized void wentIdle(NormalizerConnection connection) {
    idle.remove(connection);
    if (connection.isClosed()) {
      connections.remove(connection.pool(), connection);
    } else {
      idle.put(connection, System.nanoTime());
    }
  }
}
